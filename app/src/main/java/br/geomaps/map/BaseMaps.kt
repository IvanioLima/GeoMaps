package br.geomaps.map

import br.geomaps.core.Json
import br.geomaps.core.jObj
import br.geomaps.core.jStr
import br.geomaps.data.Db
import br.geomaps.data.Net
import br.geomaps.data.SettingsStore

/** Definição de uma base cartográfica. */
data class BaseMap(
    val id: String,
    val name: String,
    val group: String,
    /** Modelos de URL de tiles raster (null para estilo vetorial). */
    val tiles: List<String>? = null,
    val styleUrl: String? = null,
    val attribution: String = "",
    val maxZoom: Int = 19,
    val tileSize: Int = 256,
    /** Permite baixar áreas para uso offline. */
    val offline: Boolean = false,
    val needsKey: String? = null,
    val overlay: Boolean = false,
    val opacity: Float = 1f,
)

object BaseMaps {
    const val OSM_VECTOR_STYLE = "https://tiles.openfreemap.org/styles/liberty"

    val builtIn = listOf(
        BaseMap("g_road", "Google — Padrão", "Google", needsKey = "google", attribution = "© Google", maxZoom = 21),
        BaseMap("g_terrain", "Google — Terreno", "Google", needsKey = "google", attribution = "© Google", maxZoom = 21),
        BaseMap("g_sat", "Google — Satélite", "Google", needsKey = "google", attribution = "© Google", maxZoom = 21),
        BaseMap(
            "esri", "Esri — World Imagery", "Esri",
            tiles = listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
            attribution = "Esri, Maxar, Earthstar Geographics", maxZoom = 19, offline = true,
        ),
        BaseMap("osm", "OpenStreetMap — Padrão", "OpenStreetMap", tiles = listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png"), attribution = "© OpenStreetMap", maxZoom = 19),
        BaseMap("osm_hot", "OpenStreetMap — Humanitário (HOT)", "OpenStreetMap",
            tiles = listOf("https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png", "https://b.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png"),
            attribution = "© OpenStreetMap, HOT", maxZoom = 19),
        BaseMap("otm", "OpenTopoMap (relevo)", "OpenStreetMap",
            tiles = listOf("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "https://b.tile.opentopomap.org/{z}/{x}/{y}.png", "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"),
            attribution = "© OpenStreetMap, SRTM | OpenTopoMap (CC-BY-SA)", maxZoom = 17),
        BaseMap("osm_vec", "OSM Vetorial (offline por região)", "OpenStreetMap", styleUrl = OSM_VECTOR_STYLE,
            attribution = "© OpenMapTiles © OpenStreetMap | OpenFreeMap", maxZoom = 20, offline = true),
    )

    fun custom(s: Db.CustomSource): BaseMap {
        val url = when (s.type) {
            "wms" -> {
                val base = s.url.trim()
                val sep = if (base.contains('?')) (if (base.endsWith("?") || base.endsWith("&")) "" else "&") else "?"
                base + sep + "SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=" + s.layers.trim() +
                    "&STYLES=&FORMAT=image/png&TRANSPARENT=TRUE&SRS=EPSG:3857&WIDTH=${s.tileSize}&HEIGHT=${s.tileSize}&BBOX={bbox-epsg-3857}"
            }
            else -> s.url.trim().replace("{TileMatrix}", "{z}").replace("{TileCol}", "{x}").replace("{TileRow}", "{y}")
        }
        return BaseMap("c_${s.id}", s.name, "Personalizadas", tiles = listOf(url), maxZoom = s.maxZoom, tileSize = s.tileSize,
            offline = s.allowOffline, overlay = s.overlay, opacity = s.opacity, attribution = s.name)
    }

    /**
     * Sessão da Map Tiles API (2D) do Google. O token vale ~2 semanas e fica guardado.
     * O cache dos tiles é o cache HTTP do MapLibre, que respeita os cabeçalhos Cache-Control.
     */
    fun googleSession(settings: SettingsStore, id: String): String? {
        val key = settings.value.googleKey
        if (key.isBlank()) return null
        val cacheKey = "gsession_${id}_${key.hashCode()}"
        val cached = settings.getString(cacheKey)
        val exp = settings.getLong("${cacheKey}_exp")
        if (cached != null && exp > System.currentTimeMillis() / 1000 + 3600) return cached
        val body = when (id) {
            "g_sat" -> """{"mapType":"satellite","language":"pt-BR","region":"BR"}"""
            "g_terrain" -> """{"mapType":"terrain","language":"pt-BR","region":"BR","layerTypes":["layerRoadmap"]}"""
            else -> """{"mapType":"roadmap","language":"pt-BR","region":"BR"}"""
        }
        return runCatching {
            val c = Net.open("https://tile.googleapis.com/v1/createSession?key=$key")
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                throw IllegalStateException("Google Map Tiles API: HTTP ${c.responseCode} $err")
            }
            val m = Json.parse(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jObj()!!
            val session = m["session"].jStr()!!
            val expiry = m["expiry"].jStr()?.toLongOrNull() ?: (System.currentTimeMillis() / 1000 + 7 * 86400)
            settings.putString(cacheKey, session)
            settings.putLong("${cacheKey}_exp", expiry)
            session
        }.getOrElse { throw it }
    }

    fun googleTiles(session: String, key: String) = listOf("https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session=$session&key=$key")

    /** Monta o JSON de estilo para bases raster (+ sobreposições e áreas offline). */
    fun rasterStyle(
        base: BaseMap, tiles: List<String>, overlays: List<BaseMap>, offlineLayers: List<OfflineRasterLayer>, glyphs: String,
    ): String {
        val sources = LinkedHashMap<String, Any?>()
        val layers = ArrayList<Any?>()
        layers += mapOf("id" to "background", "type" to "background", "paint" to mapOf("background-color" to "#e8e4dc"))
        sources["base"] = mapOf("type" to "raster", "tiles" to tiles, "tileSize" to base.tileSize, "maxzoom" to base.maxZoom, "attribution" to base.attribution)
        layers += mapOf("id" to "base", "type" to "raster", "source" to "base")
        for (o in offlineLayers) {
            sources[o.id] = mapOf(
                "type" to "raster", "tiles" to listOf(o.urlTemplate), "tileSize" to 256, "minzoom" to o.minZoom, "maxzoom" to o.maxZoom,
                "bounds" to listOf(o.west, o.south, o.east, o.north),
            )
            layers += mapOf("id" to o.id, "type" to "raster", "source" to o.id)
        }
        overlays.forEachIndexed { i, o ->
            val id = "ovl_$i"
            sources[id] = mapOf("type" to "raster", "tiles" to (o.tiles ?: emptyList()), "tileSize" to o.tileSize, "maxzoom" to o.maxZoom, "attribution" to o.attribution)
            layers += mapOf("id" to id, "type" to "raster", "source" to id, "paint" to mapOf("raster-opacity" to o.opacity.toDouble()))
        }
        return Json.write(mapOf(
            "version" to 8, "name" to base.name,
            "glyphs" to glyphs,
            "sources" to sources, "layers" to layers,
        ))
    }
}

data class OfflineRasterLayer(
    val id: String, val urlTemplate: String, val minZoom: Int, val maxZoom: Int,
    val south: Double, val west: Double, val north: Double, val east: Double,
)
