package br.geomaps.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import br.geomaps.core.Coord
import br.geomaps.core.GeoMath
import br.geomaps.core.TileMath
import br.geomaps.data.Db
import br.geomaps.data.Net
import br.geomaps.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(val done: Long, val total: Long, val failed: Long = 0, val message: String = "")

/**
 * Mapas offline: tiles raster baixados para arquivos (fontes que permitem), regiões vetoriais do
 * MapLibre (OSM vetorial) e folhas do MDE. Mantém o total abaixo do limite (padrão 2 GB),
 * apagando primeiro as áreas baixadas há mais tempo.
 */
class OfflineStore(
    private val context: Context,
    private val db: Db,
    private val settings: SettingsStore,
    val dem: DemManager,
) {
    val tilesRoot = File(context.filesDir, "tiles").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val jobs = HashMap<Long, Job>()
    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgress>> = _progress
    private val _areas = MutableStateFlow<List<Db.OfflineArea>>(emptyList())
    val areas: StateFlow<List<Db.OfflineArea>> = _areas
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages

    init {
        scope.launch { refresh() }
    }

    fun consumeMessage() { _messages.value = null }

    fun refresh() { _areas.value = db.offlineAreas() }

    private fun setProgress(id: Long, p: DownloadProgress?) {
        val m = _progress.value.toMutableMap()
        if (p == null) m.remove(id) else m[id] = p
        _progress.value = m
    }

    fun areaDir(id: Long) = File(tilesRoot, id.toString())

    /** Camadas raster offline para exibir junto da base [baseId]. */
    fun rasterLayersFor(baseId: String): List<OfflineRasterLayer> = _areas.value
        .filter { it.kind == "raster" && it.source == baseId && it.size > 0 }
        .sortedBy { it.created }
        .map {
            OfflineRasterLayer(
                "off_${it.id}", "file://" + areaDir(it.id).absolutePath + "/{z}/{x}/{y}.img",
                it.minZoom, it.maxZoom, it.minLat, it.minLon, it.maxLat, it.maxLon,
            )
        }

    fun estimateTiles(b: GeoMath.Bounds, ring: List<Coord>?, minZ: Int, maxZ: Int) = TileMath.count(b, minZ, maxZ, ring)

    /** Tamanho médio estimado por tile (bytes). */
    fun avgTileBytes(kind: String) = when (kind) { "vector" -> 35_000L; else -> 22_000L }

    fun totalBytes(): Long = _areas.value.sumOf { it.size }

    fun limitBytes() = settings.value.cacheLimitMb.toLong() * 1024 * 1024

    // ------------------------------------------------------------------ raster

    fun startRaster(name: String, base: BaseMap, urlTemplate: String, b: GeoMath.Bounds, ring: List<Coord>?, minZ: Int, maxZ: Int): Long {
        val area = Db.OfflineArea(
            name = name, kind = "raster", source = base.id, minLat = b.minLat, minLon = b.minLon, maxLat = b.maxLat, maxLon = b.maxLon,
            ring = ring, minZoom = minZ, maxZoom = maxZ, status = "baixando",
        )
        val id = db.saveOfflineArea(area)
        refresh()
        val total = TileMath.count(b, minZ, maxZ, ring)
        setProgress(id, DownloadProgress(0, total))
        jobs[id] = scope.launch {
            val dir = areaDir(id).apply { mkdirs() }
            val done = AtomicLong(0); val failed = AtomicLong(0); val bytes = AtomicLong(0)
            val ch = Channel<TileMath.Tile>(256)
            coroutineScope {
                launch { for (t in TileMath.tiles(b, minZ, maxZ, ring)) { if (!isActive) break; ch.send(t) }; ch.close() }
                repeat(4) {
                    launch {
                        for (t in ch) {
                            val f = File(dir, "${t.z}/${t.x}/${t.y}.img")
                            if (!f.exists()) {
                                var ok = false
                                for (attempt in 0 until 3) {
                                    ok = runCatching {
                                        val c = Net.open(TileMath.url(urlTemplate, t), 20000)
                                        if (c.responseCode in 200..299) {
                                            val data = c.inputStream.use { it.readBytes() }
                                            f.parentFile?.mkdirs()
                                            f.writeBytes(data)
                                            bytes.addAndGet(data.size.toLong())
                                            true
                                        } else c.responseCode == 404
                                    }.getOrDefault(false)
                                    if (ok) break
                                }
                                if (!ok) failed.incrementAndGet()
                            } else bytes.addAndGet(f.length())
                            val d = done.incrementAndGet()
                            if (d % 25 == 0L || d == total) setProgress(id, DownloadProgress(d, total, failed.get()))
                        }
                    }
                }
            }
            val size = dirSize(dir)
            db.saveOfflineArea(area.copy(id = id, size = size, tiles = done.get(), status = if (failed.get() > 0) "concluída (${failed.get()} falhas)" else "concluída"))
            setProgress(id, null)
            refresh()
            enforceLimit(id)
            _messages.value = "Download offline concluído: $name"
        }
        return id
    }

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------------ vetorial (MapLibre)

    fun startVector(name: String, styleUrl: String, b: GeoMath.Bounds, ring: List<Coord>?, minZ: Int, maxZ: Int) {
        val area = Db.OfflineArea(
            name = name, kind = "vector", source = "osm_vec", minLat = b.minLat, minLon = b.minLon, maxLat = b.maxLat, maxLon = b.maxLon,
            ring = ring, minZoom = minZ, maxZoom = maxZ, status = "baixando",
        )
        scope.launch {
            val id = db.saveOfflineArea(area)
            refresh()
            withContext(Dispatchers.Main) {
                val ratio = context.resources.displayMetrics.density
                val def = if (ring != null && ring.size >= 3) {
                    val pts = (ring + ring.first()).map { Point.fromLngLat(it.lon, it.lat) }
                    OfflineGeometryRegionDefinition(styleUrl, Polygon.fromLngLats(listOf(pts)), minZ.toDouble(), maxZ.toDouble(), ratio)
                } else {
                    val bounds = LatLngBounds.Builder().include(LatLng(b.minLat, b.minLon)).include(LatLng(b.maxLat, b.maxLon)).build()
                    OfflineTilePyramidRegionDefinition(styleUrl, bounds, minZ.toDouble(), maxZ.toDouble(), ratio)
                }
                val meta = "{\"area\":$id}".toByteArray()
                setProgress(id, DownloadProgress(0, 1, message = "Preparando…"))
                OfflineManager.getInstance(context).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        scope.launch { db.saveOfflineArea(area.copy(id = id, regionId = offlineRegion.id)); refresh() }
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                setProgress(id, DownloadProgress(status.completedResourceCount, status.requiredResourceCount))
                                if (status.isComplete) {
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    offlineRegion.setObserver(null)
                                    scope.launch {
                                        db.saveOfflineArea(area.copy(id = id, regionId = offlineRegion.id, size = status.completedResourceSize,
                                            tiles = status.completedTileCount, status = "concluída"))
                                        setProgress(id, null)
                                        refresh()
                                        enforceLimit(id)
                                        _messages.value = "Download offline concluído: $name"
                                    }
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                setProgress(id, DownloadProgress(0, 1, message = "Erro: ${error.message}"))
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                _messages.value = "Limite de tiles atingido ($limit)"
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        setProgress(id, null)
                        scope.launch { db.saveOfflineArea(area.copy(id = id, status = "erro: $error")); refresh() }
                        _messages.value = "Erro ao criar área offline: $error"
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------ MDE

    fun startDem(name: String, b: GeoMath.Bounds, src: String) {
        scope.launch {
            val area = Db.OfflineArea(name = name, kind = "dem", source = src, minLat = b.minLat, minLon = b.minLon, maxLat = b.maxLat, maxLon = b.maxLon,
                minZoom = 0, maxZoom = 0, status = "baixando")
            val id = db.saveOfflineArea(area)
            refresh()
            val job = coroutineContext[Job]
            try {
                dem.download(b, src, { d, t -> setProgress(id, DownloadProgress(d.toLong(), t.toLong(), message = "Folhas do MDE")) }, { job?.isActive == false })
                val size = dem.sizeFor(b, src)
                db.saveOfflineArea(area.copy(id = id, size = size, tiles = dem.tilesFor(b).size.toLong(), status = "concluída"))
                _messages.value = "MDE baixado: $name"
            } catch (e: Exception) {
                db.saveOfflineArea(area.copy(id = id, status = "erro: ${e.message}"))
                _messages.value = "Erro ao baixar MDE: ${e.message}"
            }
            setProgress(id, null)
            refresh()
            enforceLimit(id)
        }.also { /* jobs não guardados: o MDE é rápido e pode ser repetido */ }
    }

    // ------------------------------------------------------------------ exclusão e limite

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        setProgress(id, null)
    }

    fun delete(area: Db.OfflineArea, after: (() -> Unit)? = null) {
        cancel(area.id)
        when (area.kind) {
            "raster" -> scope.launch { areaDir(area.id).deleteRecursively(); db.deleteOfflineArea(area.id); refresh(); after?.invoke() }
            "dem" -> scope.launch {
                val others = db.offlineAreas().filter { it.kind == "dem" && it.id != area.id }
                    .map { GeoMath.Bounds(it.minLat, it.minLon, it.maxLat, it.maxLon) to it.source }
                dem.deleteArea(GeoMath.Bounds(area.minLat, area.minLon, area.maxLat, area.maxLon), area.source, others)
                db.deleteOfflineArea(area.id); refresh(); after?.invoke()
            }
            "vector" -> main.post {
                val rid = area.regionId
                if (rid == null) { scope.launch { db.deleteOfflineArea(area.id); refresh(); after?.invoke() }; return@post }
                OfflineManager.getInstance(context).getOfflineRegion(rid, object : OfflineManager.GetOfflineRegionCallback {
                    override fun onRegion(offlineRegion: OfflineRegion) {
                        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() { scope.launch { db.deleteOfflineArea(area.id); refresh(); after?.invoke() } }
                            override fun onError(error: String) { _messages.value = "Erro ao apagar: $error" }
                        })
                    }
                    override fun onRegionNotFound() { scope.launch { db.deleteOfflineArea(area.id); refresh(); after?.invoke() } }
                    override fun onError(error: String) { _messages.value = "Erro ao apagar: $error" }
                })
            }
        }
    }

    private val enforcing = AtomicInteger(0)

    /** Apaga as áreas mais antigas até o total ficar abaixo do limite. */
    fun enforceLimit(exceptId: Long? = null) {
        if (enforcing.getAndIncrement() > 0) { enforcing.decrementAndGet(); return }
        try {
            val limit = limitBytes()
            val list = db.offlineAreas().filter { _progress.value[it.id] == null }.sortedBy { it.created }.toMutableList()
            var total = list.sumOf { it.size }
            val removed = ArrayList<String>()
            while (total > limit) {
                val victim = list.firstOrNull { it.id != exceptId } ?: break
                list.remove(victim)
                total -= victim.size
                removed += victim.name
                delete(victim)
            }
            if (removed.isNotEmpty()) _messages.value = "Limite de ${settings.value.cacheLimitMb} MB atingido: removidas as áreas mais antigas (${removed.joinToString()})"
        } finally {
            enforcing.decrementAndGet()
        }
    }

    /** Cache ambiente do MapLibre (navegação online, inclusive Google respeitando Cache-Control). */
    fun configureAmbientCache() {
        main.post {
            runCatching { OfflineManager.getInstance(context).setMaximumAmbientCacheSize(150L * 1024 * 1024, null) }
        }
    }

    fun clearAmbientCache(done: (String) -> Unit) {
        main.post {
            OfflineManager.getInstance(context).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = done("Cache de navegação limpo")
                override fun onError(message: String) = done("Erro: $message")
            })
        }
    }
}
