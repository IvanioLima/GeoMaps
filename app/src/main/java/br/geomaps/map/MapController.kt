package br.geomaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.view.Gravity
import br.geomaps.core.Coord
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeoMath
import br.geomaps.core.Geom
import br.geomaps.core.Json
import br.geomaps.data.IconFactory
import br.geomaps.location.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin

data class CameraInfo(
    val lat: Double = -5.8,
    val lon: Double = -36.6,
    val zoom: Double = 6.0,
    val bearing: Double = 0.0,
    val metersPerPixel: Double = 1000.0,
)

/** Ponte entre o estado do app e o MapLibre: fontes, camadas, ícones, câmera e toques. */
class MapController(private val context: Context, private val icons: IconFactory) {
    var map: MapLibreMap? = null
        private set
    private var style: Style? = null
    private val density = context.resources.displayMetrics.density
    private var font = arrayOf("GeoMapsSans")
    private val addedImages = HashSet<String>()

    private val _camera = MutableStateFlow(CameraInfo())
    val camera: StateFlow<CameraInfo> = _camera
    private val _styleReady = MutableStateFlow(0)
    /** Muda cada vez que um estilo termina de carregar. */
    val styleReady: StateFlow<Int> = _styleReady

    var onClick: ((Double, Double, Float, Float) -> Unit)? = null
    var onLongClick: ((Double, Double) -> Unit)? = null
    var onUserGesture: (() -> Unit)? = null

    // Últimos dados enviados (reaplicados quando o estilo muda)
    private val lastData = HashMap<String, String>()
    private var lastFeatures: List<GeoFeature> = emptyList()
    private var labels = true

    companion object {
        const val SRC_FEATURES = "gm"
        const val SRC_SEL = "gm-sel"
        const val SRC_REC = "gm-rec"
        const val SRC_DRAW = "gm-draw"
        const val SRC_MEAS = "gm-meas"
        const val SRC_NAV = "gm-nav"
        const val SRC_PROF = "gm-prof"
        const val SRC_AREA = "gm-area"
        const val SRC_LOC = "gm-loc"
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        val QUERY_LAYERS = arrayOf("gm-point", "gm-line", "gm-fill-line", "gm-fill")

        fun rgba(argb: Int): String {
            val a = ((argb ushr 24) and 0xFF) / 255.0
            return "rgba(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF},${"%.3f".format(java.util.Locale.US, a)})"
        }
    }

    fun attach(m: MapLibreMap) {
        map = m
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        m.uiSettings.isAttributionEnabled = true
        m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
        m.uiSettings.setAttributionMargins((84 * density).toInt(), 0, 0, (20 * density).toInt())
        m.setMaxZoomPreference(22.0)
        m.addOnCameraMoveListener { updateCamera() }
        m.addOnCameraIdleListener { updateCamera() }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture?.invoke()
        }
        m.addOnMapClickListener { ll ->
            val p = m.projection.toScreenLocation(ll)
            onClick?.invoke(ll.latitude, ll.longitude, p.x, p.y)
            true
        }
        m.addOnMapLongClickListener { ll ->
            onLongClick?.invoke(ll.latitude, ll.longitude)
            true
        }
        updateCamera()
    }

    private fun updateCamera() {
        val m = map ?: return
        val cp = m.cameraPosition
        val t = cp.target ?: return
        _camera.value = CameraInfo(t.latitude, t.longitude, cp.zoom, cp.bearing, m.projection.getMetersPerPixelAtLatitude(t.latitude))
    }

    // ------------------------------------------------------------------ estilo

    fun setStyleJson(json: String, vector: Boolean = false) {
        val m = map ?: return
        font = if (vector) arrayOf("Noto Sans Regular") else arrayOf("GeoMapsSans")
        m.setStyle(Style.Builder().fromJson(json)) { onStyle(it) }
    }

    fun setStyleUrl(url: String) {
        val m = map ?: return
        font = arrayOf("Noto Sans Regular")
        m.setStyle(Style.Builder().fromUri(url)) { onStyle(it) }
    }

    private fun onStyle(s: Style) {
        style = s
        addedImages.clear()
        runCatching { s.addImage("gm-arrow", icons.locationArrow()) }
        fun src(id: String) = s.addSource(GeoJsonSource(id, lastData[id] ?: EMPTY))
        for (id in listOf(SRC_FEATURES, SRC_SEL, SRC_REC, SRC_DRAW, SRC_MEAS, SRC_NAV, SRC_PROF, SRC_AREA, SRC_LOC)) runCatching { src(id) }

        val kind = Expression.get("kind")
        fun add(l: Layer) = runCatching { s.addLayer(l) }

        add(FillLayer("gm-fill", SRC_FEATURES).withFilter(Expression.eq(kind, "A")).withProperties(
            PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
        ))
        add(LineLayer("gm-fill-line", SRC_FEATURES).withFilter(Expression.eq(kind, "A")).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(Expression.get("width")),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("gm-sel-line", SRC_SEL).withFilter(Expression.neq(kind, "P")).withProperties(
            PropertyFactory.lineColor("#00E5FF"), PropertyFactory.lineWidth(9f), PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("gm-line", SRC_FEATURES).withFilter(Expression.eq(kind, "L")).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(Expression.get("width")),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("gm-rec", SRC_REC).withProperties(
            PropertyFactory.lineColor("#D500F9"), PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(FillLayer("gm-area-fill", SRC_AREA).withProperties(PropertyFactory.fillColor("#1E88E5"), PropertyFactory.fillOpacity(0.15f)))
        add(LineLayer("gm-area-line", SRC_AREA).withProperties(
            PropertyFactory.lineColor("#1E88E5"), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
        ))
        add(LineLayer("gm-nav", SRC_NAV).withProperties(
            PropertyFactory.lineColor("#FF6D00"), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        ))
        add(CircleLayer("gm-sel-point", SRC_SEL).withFilter(Expression.eq(kind, "P")).withProperties(
            PropertyFactory.circleRadius(20f), PropertyFactory.circleColor("#00E5FF"), PropertyFactory.circleOpacity(0.3f),
            PropertyFactory.circleStrokeColor("#00B8D4"), PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleTranslate(arrayOf(0f, -14f)),
        ))
        add(SymbolLayer("gm-point", SRC_FEATURES).withFilter(Expression.eq(kind, "P")).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(font),
            PropertyFactory.textSize(12f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.2f)),
            PropertyFactory.textColor("#1A1A1A"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.6f),
            PropertyFactory.textOptional(true),
            PropertyFactory.textMaxWidth(10f),
        ))
        // Desenho / edição
        add(FillLayer("gm-draw-fill", SRC_DRAW).withFilter(Expression.eq(Expression.geometryType(), "Polygon")).withProperties(
            PropertyFactory.fillColor("#FF6D00"), PropertyFactory.fillOpacity(0.18f),
        ))
        add(LineLayer("gm-draw-line", SRC_DRAW).withFilter(Expression.neq(Expression.geometryType(), "Point")).withProperties(
            PropertyFactory.lineColor("#FF6D00"), PropertyFactory.lineWidth(3f), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(CircleLayer("gm-draw-mid", SRC_DRAW).withFilter(Expression.eq(Expression.get("t"), "m")).withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleOpacity(0.85f),
            PropertyFactory.circleStrokeColor("#FF6D00"), PropertyFactory.circleStrokeWidth(1.5f),
        ))
        add(CircleLayer("gm-draw-vtx", SRC_DRAW).withFilter(Expression.eq(Expression.get("t"), "v")).withProperties(
            PropertyFactory.circleRadius(Expression.switchCase(Expression.eq(Expression.get("s"), 1), Expression.literal(9f), Expression.literal(6f))),
            PropertyFactory.circleColor(Expression.switchCase(Expression.eq(Expression.get("s"), 1), Expression.color(0xFFD50000.toInt()), Expression.color(0xFFFF6D00.toInt()))),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
        ))
        add(LineLayer("gm-meas-line", SRC_MEAS).withFilter(Expression.neq(Expression.geometryType(), "Point")).withProperties(
            PropertyFactory.lineColor("#2962FF"), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(2f, 1f)),
        ))
        add(FillLayer("gm-meas-fill", SRC_MEAS).withFilter(Expression.eq(Expression.geometryType(), "Polygon")).withProperties(
            PropertyFactory.fillColor("#2962FF"), PropertyFactory.fillOpacity(0.12f),
        ))
        add(CircleLayer("gm-meas-vtx", SRC_MEAS).withFilter(Expression.eq(Expression.geometryType(), "Point")).withProperties(
            PropertyFactory.circleRadius(5f), PropertyFactory.circleColor("#2962FF"), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
        ))
        add(CircleLayer("gm-prof", SRC_PROF).withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#FFD600"), PropertyFactory.circleStrokeColor("#212121"), PropertyFactory.circleStrokeWidth(2.5f),
        ))
        // Posição atual
        add(FillLayer("gm-loc-acc", SRC_LOC).withFilter(Expression.eq(Expression.geometryType(), "Polygon")).withProperties(
            PropertyFactory.fillColor("#1565C0"), PropertyFactory.fillOpacity(0.12f),
        ))
        add(CircleLayer("gm-loc-dot", SRC_LOC).withFilter(Expression.all(Expression.eq(Expression.geometryType(), "Point"), Expression.eq(Expression.get("h"), 0))).withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#1565C0"), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ))
        add(SymbolLayer("gm-loc-arrow", SRC_LOC).withFilter(Expression.all(Expression.eq(Expression.geometryType(), "Point"), Expression.eq(Expression.get("h"), 1))).withProperties(
            PropertyFactory.iconImage("gm-arrow"), PropertyFactory.iconRotate(Expression.get("b")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        ))
        setFeatures(lastFeatures, labels)
        _styleReady.value = _styleReady.value + 1
    }

    private fun push(id: String, json: String) {
        lastData[id] = json
        val s = style ?: return
        if (!s.isFullyLoaded) return
        s.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
    }

    // ------------------------------------------------------------------ GeoJSON

    private fun pos(c: Coord) = listOf(c.lon, c.lat)

    private fun geometry(g: Geom): Map<String, Any?> = when (g) {
        is Geom.Pt -> mapOf("type" to "Point", "coordinates" to pos(g.c))
        is Geom.Line -> mapOf("type" to "MultiLineString", "coordinates" to g.parts.map { p -> p.map { pos(it) } })
        is Geom.Poly -> mapOf("type" to "MultiPolygon", "coordinates" to g.polys.map { poly -> poly.map { r -> (r + r.first()).map { pos(it) } } })
    }

    private fun fc(features: List<Map<String, Any?>>) = Json.write(mapOf("type" to "FeatureCollection", "features" to features))
    private fun feat(geom: Map<String, Any?>, props: Map<String, Any?> = emptyMap()) = mapOf("type" to "Feature", "properties" to props, "geometry" to geom)

    fun setFeatures(features: List<GeoFeature>, showLabels: Boolean) {
        lastFeatures = features
        labels = showLabels
        val s = style
        val out = ArrayList<Map<String, Any?>>(features.size)
        for (f in features) {
            val kind = when (f.geom) { is Geom.Pt -> "P"; is Geom.Line -> "L"; is Geom.Poly -> "A" }
            if (f.geom.allCoords().isEmpty()) continue
            val props = LinkedHashMap<String, Any?>()
            props["id"] = f.id
            props["kind"] = kind
            props["label"] = if (showLabels) f.name else ""
            props["color"] = rgba(f.style.color)
            props["fill"] = rgba(f.style.fill)
            props["width"] = f.style.width.toDouble()
            if (kind == "P") {
                val imgId = icons.imageId(f.style.icon, f.style.color, f.style.iconScale)
                if (s != null && s.isFullyLoaded && imgId !in addedImages) {
                    runCatching { s.addImage(imgId, icons.bitmap(f.style.icon, f.style.color, f.style.iconScale)) }
                    addedImages += imgId
                }
                props["icon"] = imgId
            }
            out += feat(geometry(f.geom), props)
        }
        push(SRC_FEATURES, fc(out))
    }

    /** Força recriar o desenho de um ícone (após importar imagens novas). */
    fun invalidateIcons() { addedImages.clear(); setFeatures(lastFeatures, labels) }

    fun setSelection(f: GeoFeature?) {
        if (f == null) { push(SRC_SEL, EMPTY); return }
        val kind = when (f.geom) { is Geom.Pt -> "P"; is Geom.Line -> "L"; is Geom.Poly -> "A" }
        val g = if (f.geom is Geom.Poly) mapOf("type" to "MultiLineString", "coordinates" to f.geom.polys.flatMap { p -> p.map { r -> (r + r.first()).map { pos(it) } } }) else geometry(f.geom)
        push(SRC_SEL, fc(listOf(feat(g, mapOf("kind" to kind)))))
    }

    fun setRecording(segs: List<List<Coord>>) {
        val parts = segs.filter { it.size >= 2 }
        push(SRC_REC, if (parts.isEmpty()) EMPTY else fc(listOf(feat(mapOf("type" to "MultiLineString", "coordinates" to parts.map { p -> p.map { pos(it) } })))))
    }

    /** Geometria em edição/desenho com vértices (t=v) e pontos médios (t=m) para inserir. */
    fun setDraw(coords: List<Coord>, closed: Boolean, selected: Set<Int> = emptySet(), midpoints: Boolean = false) {
        if (coords.isEmpty()) { push(SRC_DRAW, EMPTY); return }
        val out = ArrayList<Map<String, Any?>>()
        if (closed && coords.size >= 3) out += feat(mapOf("type" to "Polygon", "coordinates" to listOf((coords + coords.first()).map { pos(it) })))
        else if (coords.size >= 2) out += feat(mapOf("type" to "LineString", "coordinates" to coords.map { pos(it) }))
        val step = if (coords.size > 2000) coords.size / 1000 else 1
        coords.forEachIndexed { i, c ->
            if (i % step == 0 || i in selected || i == coords.size - 1) out += feat(mapOf("type" to "Point", "coordinates" to pos(c)), mapOf("t" to "v", "i" to i, "s" to if (i in selected) 1 else 0))
        }
        if (midpoints && coords.size < 1500) {
            val n = if (closed) coords.size else coords.size - 1
            for (i in 0 until n) {
                val a = coords[i]; val b = coords[(i + 1) % coords.size]
                out += feat(mapOf("type" to "Point", "coordinates" to listOf((a.lon + b.lon) / 2, (a.lat + b.lat) / 2)), mapOf("t" to "m", "i" to i))
            }
        }
        push(SRC_DRAW, fc(out))
    }

    fun setMeasure(coords: List<Coord>, closed: Boolean) {
        if (coords.isEmpty()) { push(SRC_MEAS, EMPTY); return }
        val out = ArrayList<Map<String, Any?>>()
        if (closed && coords.size >= 3) out += feat(mapOf("type" to "Polygon", "coordinates" to listOf((coords + coords.first()).map { pos(it) })))
        else if (coords.size >= 2) out += feat(mapOf("type" to "LineString", "coordinates" to coords.map { pos(it) }))
        coords.forEach { out += feat(mapOf("type" to "Point", "coordinates" to pos(it))) }
        push(SRC_MEAS, fc(out))
    }

    fun setNavLine(from: Coord?, to: Coord?) {
        push(SRC_NAV, if (from == null || to == null) EMPTY else fc(listOf(feat(mapOf("type" to "LineString", "coordinates" to listOf(pos(from), pos(to)))))))
    }

    fun setProfileMarker(c: Coord?) {
        push(SRC_PROF, if (c == null) EMPTY else fc(listOf(feat(mapOf("type" to "Point", "coordinates" to pos(c))))))
    }

    fun setAreaPreview(ring: List<Coord>?) {
        push(SRC_AREA, if (ring == null || ring.size < 2) EMPTY else fc(listOf(feat(
            if (ring.size >= 3) mapOf("type" to "Polygon", "coordinates" to listOf((ring + ring.first()).map { pos(it) }))
            else mapOf("type" to "LineString", "coordinates" to ring.map { pos(it) }),
        ))))
    }

    fun setLocation(fix: Fix?, heading: Float?) {
        if (fix == null) { push(SRC_LOC, EMPTY); return }
        val out = ArrayList<Map<String, Any?>>()
        val acc = fix.accuracy
        if (acc != null && acc > 3) {
            val ring = (0..48).map { i ->
                val (la, lo) = GeoMath.destination(fix.lat, fix.lon, i * 7.5, acc.toDouble())
                listOf(lo, la)
            }
            out += feat(mapOf("type" to "Polygon", "coordinates" to listOf(ring)))
        }
        out += feat(mapOf("type" to "Point", "coordinates" to listOf(fix.lon, fix.lat)), mapOf("h" to if (heading != null) 1 else 0, "b" to (heading ?: 0f).toDouble()))
        push(SRC_LOC, fc(out))
    }

    // ------------------------------------------------------------------ consultas e câmera

    /** Elemento sob o toque (com tolerância de ~14 dp). */
    fun featureAt(x: Float, y: Float): Long? {
        val m = map ?: return null
        val r = 14 * density
        val list = runCatching { m.queryRenderedFeatures(RectF(x - r, y - r, x + r, y + r), *QUERY_LAYERS) }.getOrNull() ?: return null
        // prioridade: pontos, depois linhas, depois áreas
        val order = listOf("P", "L", "A")
        return list.sortedBy { f -> order.indexOf(f.getStringProperty("kind") ?: "A") }
            .firstNotNullOfOrNull { it.getNumberProperty("id")?.toLong() }
    }

    /** Índice do vértice/ponto médio de edição sob o toque: (tipo, índice). */
    fun drawHandleAt(x: Float, y: Float): Pair<String, Int>? {
        val m = map ?: return null
        val r = 18 * density
        val list = runCatching { m.queryRenderedFeatures(RectF(x - r, y - r, x + r, y + r), "gm-draw-vtx", "gm-draw-mid") }.getOrNull() ?: return null
        val best = list.minByOrNull { f ->
            val p = (f.geometry() as? org.maplibre.geojson.Point) ?: return@minByOrNull Float.MAX_VALUE
            val sp = m.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
            (sp.x - x) * (sp.x - x) + (sp.y - y) * (sp.y - y)
        } ?: return null
        val t = best.getStringProperty("t") ?: return null
        val i = best.getNumberProperty("i")?.toInt() ?: return null
        return t to i
    }

    fun toScreen(lat: Double, lon: Double): PointF? = map?.projection?.toScreenLocation(LatLng(lat, lon))
    fun fromScreen(x: Float, y: Float): Coord? = map?.projection?.fromScreenLocation(PointF(x, y))?.let { Coord(it.latitude, it.longitude) }
    fun center(): Coord = _camera.value.let { Coord(it.lat, it.lon) }

    fun flyTo(lat: Double, lon: Double, zoom: Double? = null, duration: Int = 700) {
        val m = map ?: return
        val z = zoom ?: maxOf(m.cameraPosition.zoom, 15.0)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z), duration)
    }

    fun follow(lat: Double, lon: Double, bearing: Double?) {
        val m = map ?: return
        val b = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(m.cameraPosition.zoom)
        if (bearing != null) b.bearing(bearing)
        m.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 450)
    }

    fun fit(coords: List<Coord>, paddingDp: Int = 56) {
        val m = map ?: return
        if (coords.isEmpty()) return
        if (coords.size == 1 || coords.all { it.lat == coords[0].lat && it.lon == coords[0].lon }) { flyTo(coords[0].lat, coords[0].lon, 17.0); return }
        val b = LatLngBounds.Builder()
        coords.forEach { b.include(LatLng(it.lat, it.lon)) }
        runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), (paddingDp * density).toInt()), 700) }
    }

    fun setBearing(b: Double) {
        map?.animateCamera(CameraUpdateFactory.bearingTo(b), 300)
    }

    fun zoomBy(d: Double) {
        map?.animateCamera(if (d > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut(), 250)
    }

    fun snapshot(cb: (Bitmap) -> Unit) {
        map?.snapshot { cb(it) }
    }

    /** Converte metros em pixels na latitude atual (para a escala). */
    fun metersPerPixel() = _camera.value.metersPerPixel

    @Suppress("unused")
    private fun circle(lat: Double, lon: Double, r: Double) = (0..36).map { i ->
        val a = Math.toRadians(i * 10.0)
        Coord(lat + r * cos(a) / 111320.0, lon + r * sin(a) / (111320.0 * cos(Math.toRadians(lat))))
    }
}
