package br.geomaps.map

import android.content.Context
import br.geomaps.core.DemNames
import br.geomaps.core.DemTile
import br.geomaps.core.GeoMath
import br.geomaps.core.GeoTiffTile
import br.geomaps.core.HgtTile
import br.geomaps.data.Net
import br.geomaps.data.SettingsStore
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/**
 * Modelo digital de elevação offline (Copernicus GLO-30 e SRTM 1"), baixado por folhas de 1°×1°.
 */
class DemManager(context: Context, private val settings: SettingsStore) {
    val root = File(context.filesDir, "dem").apply { mkdirs() }
    private val open = object : LinkedHashMap<String, DemTile?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DemTile?>?): Boolean {
            if (size > 6) { runCatching { eldest?.value?.close() }; return true }
            return false
        }
    }

    fun dir(src: String) = File(root, src).apply { mkdirs() }

    fun file(src: String, la: Int, lo: Int): File =
        if (src == "srtm") File(dir(src), DemNames.hgtName(la, lo) + ".hgt") else File(dir(src), DemNames.copernicusName(la, lo) + ".tif")

    private fun missingMarker(src: String, la: Int, lo: Int) = File(dir(src), "${src}_${la}_${lo}.none")

    fun tilesFor(b: GeoMath.Bounds) = DemNames.tilesFor(b)

    /** Baixa as folhas da área. Retorna bytes gravados. Folhas sobre o oceano (inexistentes) são ignoradas. */
    fun download(b: GeoMath.Bounds, src: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Long {
        val tiles = tilesFor(b)
        var bytes = 0L
        tiles.forEachIndexed { i, (la, lo) ->
            if (cancelled()) return bytes
            progress(i, tiles.size)
            val f = file(src, la, lo)
            if (f.exists() || missingMarker(src, la, lo).exists()) { bytes += f.length(); return@forEachIndexed }
            val url = if (src == "srtm") DemNames.srtmUrl(la, lo) else DemNames.copernicusUrl(la, lo)
            val c = Net.open(url, 60000)
            val code = c.responseCode
            if (code == 404 || code == 403) { missingMarker(src, la, lo).createNewFile(); return@forEachIndexed }
            if (code !in 200..299) throw IllegalStateException("HTTP $code ao baixar ${f.name}")
            val tmp = File(f.path + ".part")
            c.inputStream.use { ins ->
                val stream = if (src == "srtm") GZIPInputStream(ins) else ins
                tmp.outputStream().use { out -> stream.copyTo(out, 256 * 1024) }
            }
            tmp.renameTo(f)
            bytes += f.length()
        }
        progress(tiles.size, tiles.size)
        synchronized(open) { open.clear() }
        return bytes
    }

    fun sizeFor(b: GeoMath.Bounds, src: String): Long = tilesFor(b).sumOf { (la, lo) -> file(src, la, lo).let { if (it.exists()) it.length() else 0L } }

    fun deleteArea(b: GeoMath.Bounds, src: String, keep: List<Pair<GeoMath.Bounds, String>>) {
        synchronized(open) { open.values.forEach { runCatching { it?.close() } }; open.clear() }
        val keepTiles = keep.filter { it.second == src }.flatMap { tilesFor(it.first) }.toSet()
        for (t in tilesFor(b)) if (t !in keepTiles) { file(src, t.first, t.second).delete(); missingMarker(src, t.first, t.second).delete() }
    }

    private fun tile(src: String, la: Int, lo: Int): DemTile? {
        val key = "$src/$la/$lo"
        synchronized(open) {
            if (open.containsKey(key)) return open[key]
            val f = file(src, la, lo)
            val t = if (!f.exists()) null else runCatching {
                if (src == "srtm") HgtTile(f, la, lo) else GeoTiffTile(f)
            }.getOrNull()
            open[key] = t
            return t
        }
    }

    /** Altitude pelo MDE (fonte preferida; recorre à outra se não houver folha baixada). */
    fun sample(lat: Double, lon: Double): Double? {
        val la = floor(lat).toInt(); val lo = floor(lon).toInt()
        val pref = settings.value.demSource
        val order = if (pref == "srtm") listOf("srtm", "copernicus") else listOf("copernicus", "srtm")
        for (s in order) {
            val v = runCatching { tile(s, la, lo)?.sample(lat, lon) }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    fun hasData(lat: Double, lon: Double): Boolean {
        val la = floor(lat).toInt(); val lo = floor(lon).toInt()
        return file("copernicus", la, lo).exists() || file("srtm", la, lo).exists()
    }
}
