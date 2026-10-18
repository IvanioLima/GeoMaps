package br.geomaps.core

import java.util.Locale

/**
 * Exportação DXF (AutoCAD R12, ASCII) em coordenadas UTM, pronta para CAD.
 * Pontos → POINT + TEXT com o nome; trilhas → POLYLINE 3D; áreas → POLYLINE fechada.
 * Camadas: GM_PONTOS / GM_TRILHAS / GM_AREAS / GM_TEXTOS (ou por categoria, se houver).
 */
object Dxf {

    data class Result(val bytes: ByteArray, val zone: Int, val south: Boolean)

    fun write(features: List<GeoFeature>, prefs: CoordPrefs, textHeight: Double = 2.0): Result {
        val all = features.flatMap { it.geom.allCoords() }
        val c = GeoMath.centroid(all)
        val zone = prefs.fixedZone ?: Utm.zoneFor(c.lat, c.lon)
        val south = if (prefs.fixedZone != null) prefs.fixedSouth else c.lat < 0
        val tm = Utm.tm(zone, south, prefs.datum.ellipsoid)
        fun xy(p: Coord) = tm.forward(p.lat, p.lon)

        fun layerOf(f: GeoFeature): String {
            val base = when (f.type) { GeomType.POINT -> "GM_PONTOS"; GeomType.LINE -> "GM_TRILHAS"; GeomType.POLYGON -> "GM_AREAS" }
            if (f.category.isBlank()) return base
            return (base + "_" + f.category).uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_\\-]"), "_").take(60)
        }

        val layers = LinkedHashMap<String, Int>()
        layers["GM_TEXTOS"] = 7
        for (f in features) layers.getOrPut(layerOf(f)) { aci(f.style.color) }

        val sb = StringBuilder()
        fun g(code: Int, v: Any) { sb.append(code).append("\r\n").append(v).append("\r\n") }
        fun d(v: Double) = String.format(Locale.US, "%.4f", v)

        val pts = all.map { xy(it) }
        val minX = pts.minOfOrNull { it.first } ?: 0.0; val minY = pts.minOfOrNull { it.second } ?: 0.0
        val maxX = pts.maxOfOrNull { it.first } ?: 0.0; val maxY = pts.maxOfOrNull { it.second } ?: 0.0
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$EXTMIN"); g(10, d(minX)); g(20, d(minY)); g(30, d(0.0))
        g(9, "\$EXTMAX"); g(10, d(maxX)); g(20, d(maxY)); g(30, d(0.0))
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LAYER"); g(70, layers.size)
        for ((n, col) in layers) { g(0, "LAYER"); g(2, n); g(70, 0); g(62, col); g(6, "CONTINUOUS") }
        g(0, "ENDTAB")
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")
        for (f in features) {
            val layer = layerOf(f)
            when (val geo = f.geom) {
                is Geom.Pt -> {
                    val (x, y) = xy(geo.c)
                    val z = geo.c.ele ?: 0.0
                    g(0, "POINT"); g(8, layer); g(10, d(x)); g(20, d(y)); g(30, d(z))
                    if (f.name.isNotBlank()) {
                        g(0, "TEXT"); g(8, "GM_TEXTOS"); g(10, d(x + textHeight * 0.6)); g(20, d(y + textHeight * 0.3)); g(30, d(z))
                        g(40, d(textHeight)); g(1, f.name.replace("\n", " "))
                    }
                }
                is Geom.Line -> geo.parts.forEach { polyline(it, false, layer, ::g, ::d, ::xy) }
                is Geom.Poly -> geo.polys.forEach { p -> p.forEach { r -> polyline(r, true, layer, ::g, ::d, ::xy) } }
            }
        }
        g(0, "ENDSEC")
        g(0, "EOF")
        return Result(sb.toString().toByteArray(Charsets.ISO_8859_1), zone, south)
    }

    private fun polyline(
        c: List<Coord>, closed: Boolean, layer: String,
        g: (Int, Any) -> Unit, d: (Double) -> String, xy: (Coord) -> Pair<Double, Double>,
    ) {
        if (c.size < 2) return
        val has3d = c.any { it.ele != null }
        g(0, "POLYLINE"); g(8, layer); g(66, 1); g(10, d(0.0)); g(20, d(0.0)); g(30, d(0.0))
        g(70, (if (closed) 1 else 0) or (if (has3d) 8 else 0))
        for (p in c) {
            val (x, y) = xy(p)
            g(0, "VERTEX"); g(8, layer); g(10, d(x)); g(20, d(y)); g(30, d(p.ele ?: 0.0))
            if (has3d) g(70, 32)
        }
        g(0, "SEQEND"); g(8, layer)
    }

    /** Aproxima uma cor ARGB do índice de cores do AutoCAD (ACI básico). */
    fun aci(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val gg = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val table = listOf(
            1 to Triple(255, 0, 0), 2 to Triple(255, 255, 0), 3 to Triple(0, 255, 0), 4 to Triple(0, 255, 255),
            5 to Triple(0, 0, 255), 6 to Triple(255, 0, 255), 7 to Triple(255, 255, 255), 8 to Triple(128, 128, 128),
            30 to Triple(255, 127, 0), 9 to Triple(192, 192, 192),
        )
        return table.minByOrNull { (_, t) ->
            val dr = r - t.first; val dg = gg - t.second; val db = b - t.third
            dr * dr + dg * dg + db * db
        }!!.first
    }
}
