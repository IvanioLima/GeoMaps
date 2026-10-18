package br.geomaps.core

object GeoJsonIO {

    fun read(bytes: ByteArray, sourceName: String): ImportResult {
        val root = runCatching { Json.parse(bytes.toString(Charsets.UTF_8)).jObj() }.getOrNull()
            ?: throw GeoFormatException("GeoJSON inválido")
        val feats: List<Map<String, Any?>> = when (root["type"]) {
            "FeatureCollection" -> root["features"].jArr()?.mapNotNull { it.jObj() } ?: emptyList()
            "Feature" -> listOf(root)
            else -> listOf(mapOf("type" to "Feature", "geometry" to root, "properties" to emptyMap<String, Any?>()))
        }
        val out = ArrayList<GeoFeature>()
        val warnings = ArrayList<String>()
        for ((i, f) in feats.withIndex()) {
            val props = f["properties"].jObj() ?: emptyMap()
            val geoms = runCatching { geoms(f["geometry"].jObj()) }.getOrElse { warnings += "Elemento ${i + 1}: ${it.message}"; emptyList() }
            val name = (props["name"] ?: props["nome"] ?: props["Name"] ?: props["NOME"] ?: props["title"]).jStr() ?: "Elemento ${i + 1}"
            val desc = (props["description"] ?: props["descricao"] ?: props["desc"]).jStr() ?: ""
            val cat = (props["category"] ?: props["categoria"]).jStr() ?: ""
            val skip = setOf("name", "nome", "Name", "NOME", "title", "description", "descricao", "desc", "category", "categoria",
                "marker-color", "marker-symbol", "marker-size", "stroke", "stroke-width", "stroke-opacity", "fill", "fill-opacity")
            val attrs = LinkedHashMap<String, String>()
            for ((k, v) in props) if (k !in skip && v != null && v !is Map<*, *> && v !is List<*>) attrs[k] = v.jStr() ?: ""
            for (g in geoms) {
                out += GeoFeature(name = name, description = desc, category = cat, geom = g, attrs = attrs, style = style(props, g.type))
            }
        }
        if (out.isEmpty()) throw GeoFormatException("Nenhuma geometria encontrada no GeoJSON")
        return ImportResult(sourceName, out, emptyMap(), warnings)
    }

    private fun color(s: String?, opacity: Double?): Int? {
        val t = s?.trim()?.removePrefix("#") ?: return null
        val hex = if (t.length == 3) t.map { "$it$it" }.joinToString("") else t
        val v = hex.toLongOrNull(16) ?: return null
        val a = ((opacity ?: 1.0).coerceIn(0.0, 1.0) * 255).toInt()
        return ((a.toLong() shl 24) or (v and 0xFFFFFF)).toInt()
    }

    private fun style(p: Map<String, Any?>, t: GeomType): FeatureStyle = when (t) {
        GeomType.POINT -> FeatureStyle(color = color(p["marker-color"].jStr(), 1.0) ?: FeatureStyle.DEFAULT_LINE)
        GeomType.LINE -> FeatureStyle(
            color = color(p["stroke"].jStr(), p["stroke-opacity"].jNum()) ?: FeatureStyle.DEFAULT_LINE,
            width = p["stroke-width"].jNum()?.toFloat() ?: 4f,
        )
        GeomType.POLYGON -> FeatureStyle(
            color = color(p["stroke"].jStr(), p["stroke-opacity"].jNum()) ?: FeatureStyle.DEFAULT_LINE,
            width = p["stroke-width"].jNum()?.toFloat() ?: 2f,
            fill = color(p["fill"].jStr(), p["fill-opacity"].jNum() ?: 0.3) ?: FeatureStyle.DEFAULT_FILL,
        )
    }

    private fun c(o: Any?): Coord {
        val a = o.jArr() ?: throw GeoFormatException("coordenada inválida")
        return Coord(a[1].jNum()!!, a[0].jNum()!!, a.getOrNull(2).jNum())
    }

    private fun line(o: Any?) = o.jArr()!!.map { c(it) }
    private fun poly(o: Any?) = o.jArr()!!.map { Geom.Poly.openRing(line(it)) }.filter { it.size >= 3 }

    private fun geoms(g: Map<String, Any?>?): List<Geom> {
        if (g == null) return emptyList()
        val co = g["coordinates"]
        return when (g["type"]) {
            "Point" -> listOf(Geom.Pt(c(co)))
            "MultiPoint" -> co.jArr()!!.map { Geom.Pt(c(it)) }
            "LineString" -> listOf(Geom.Line.of(line(co)))
            "MultiLineString" -> listOf(Geom.Line(co.jArr()!!.map { line(it) }))
            "Polygon" -> listOf(Geom.Poly(listOf(poly(co))))
            "MultiPolygon" -> listOf(Geom.Poly(co.jArr()!!.map { poly(it) }))
            "GeometryCollection" -> g["geometries"].jArr()!!.flatMap { geoms(it.jObj()) }
            else -> throw GeoFormatException("tipo ${g["type"]} não suportado")
        }
    }

    // ---------------------------------------------------------------- escrita

    private fun cl(c: Coord): List<Any?> = if (c.ele != null) listOf(c.lon, c.lat, c.ele) else listOf(c.lon, c.lat)

    fun geometry(g: Geom): Map<String, Any?> = when (g) {
        is Geom.Pt -> mapOf("type" to "Point", "coordinates" to cl(g.c))
        is Geom.Line -> if (g.parts.size == 1) mapOf("type" to "LineString", "coordinates" to g.parts[0].map { cl(it) })
        else mapOf("type" to "MultiLineString", "coordinates" to g.parts.map { p -> p.map { cl(it) } })
        is Geom.Poly -> {
            fun ring(r: List<Coord>) = (if (r.isNotEmpty()) r + r.first() else r).map { cl(it) }
            if (g.polys.size == 1) mapOf("type" to "Polygon", "coordinates" to g.polys[0].map { ring(it) })
            else mapOf("type" to "MultiPolygon", "coordinates" to g.polys.map { p -> p.map { ring(it) } })
        }
    }

    private fun hex(c: Int) = String.format("#%06x", c and 0xFFFFFF)
    private fun alpha(c: Int) = Math.round(((c ushr 24) and 0xFF) / 255.0 * 100) / 100.0

    fun write(features: List<GeoFeature>, name: String): ByteArray {
        val fc = linkedMapOf<String, Any?>(
            "type" to "FeatureCollection",
            "name" to name,
            "features" to features.map { f ->
                val props = LinkedHashMap<String, Any?>()
                props["name"] = f.name
                if (f.description.isNotEmpty()) props["description"] = f.description
                if (f.category.isNotEmpty()) props["category"] = f.category
                if (f.folder.isNotEmpty()) props["folder"] = f.folder
                props.putAll(f.attrs)
                when (f.type) {
                    GeomType.POINT -> props["marker-color"] = hex(f.style.color)
                    GeomType.LINE -> { props["stroke"] = hex(f.style.color); props["stroke-width"] = f.style.width; props["stroke-opacity"] = alpha(f.style.color) }
                    GeomType.POLYGON -> {
                        props["stroke"] = hex(f.style.color); props["stroke-width"] = f.style.width
                        props["fill"] = hex(f.style.fill); props["fill-opacity"] = alpha(f.style.fill)
                    }
                }
                linkedMapOf("type" to "Feature", "properties" to props, "geometry" to geometry(f.geom))
            },
        )
        return Json.write(fc).toByteArray(Charsets.UTF_8)
    }
}
