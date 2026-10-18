package br.geomaps.core

/**
 * Serialização compacta (JSON) usada no banco interno, nos backups e no histórico de versões.
 * Vértice = [lon, lat, ele|null, time|null, acc|null] (campos nulos finais são omitidos).
 */
object Codec {

    private fun coordToList(c: Coord): List<Any?> {
        val l = arrayListOf<Any?>(round(c.lon), round(c.lat))
        if (c.ele != null || c.time != null || c.acc != null) l.add(c.ele?.let { Math.round(it * 100) / 100.0 })
        if (c.time != null || c.acc != null) l.add(c.time)
        if (c.acc != null) l.add(Math.round(c.acc * 10) / 10.0)
        return l
    }

    private fun round(v: Double) = Math.round(v * 1e8) / 1e8

    private fun listToCoord(o: Any?): Coord {
        val a = o.jArr() ?: throw GeoFormatException("vértice inválido")
        return Coord(
            lat = a[1].jNum()!!,
            lon = a[0].jNum()!!,
            ele = a.getOrNull(2).jNum(),
            time = (a.getOrNull(3) as? Number)?.toLong(),
            acc = a.getOrNull(4).jNum(),
        )
    }

    fun geomToJson(g: Geom): String = Json.write(geomToMap(g))

    fun geomToMap(g: Geom): Map<String, Any?> = when (g) {
        is Geom.Pt -> mapOf("t" to "P", "c" to coordToList(g.c))
        is Geom.Line -> mapOf("t" to "L", "c" to g.parts.map { p -> p.map { coordToList(it) } })
        is Geom.Poly -> mapOf("t" to "A", "c" to g.polys.map { poly -> poly.map { r -> r.map { coordToList(it) } } })
    }

    fun geomFromJson(s: String): Geom = geomFromMap(Json.parse(s).jObj()!!)

    fun geomFromMap(m: Map<String, Any?>): Geom = when (m["t"]) {
        "P" -> Geom.Pt(listToCoord(m["c"]))
        "L" -> Geom.Line(m["c"].jArr()!!.map { p -> p.jArr()!!.map { listToCoord(it) } })
        "A" -> Geom.Poly(m["c"].jArr()!!.map { poly -> poly.jArr()!!.map { r -> r.jArr()!!.map { listToCoord(it) } } })
        else -> throw GeoFormatException("geometria desconhecida")
    }

    fun styleToMap(s: FeatureStyle): Map<String, Any?> = mapOf(
        "c" to (s.color.toLong() and 0xFFFFFFFFL), "w" to s.width.toDouble(),
        "f" to (s.fill.toLong() and 0xFFFFFFFFL), "i" to s.icon, "s" to s.iconScale.toDouble(),
    )

    fun styleFromMap(m: Map<String, Any?>?): FeatureStyle {
        if (m == null) return FeatureStyle()
        return FeatureStyle(
            color = (m["c"] as? Number)?.toLong()?.toInt() ?: FeatureStyle.DEFAULT_LINE,
            width = m["w"].jNum()?.toFloat() ?: 4f,
            fill = (m["f"] as? Number)?.toLong()?.toInt() ?: FeatureStyle.DEFAULT_FILL,
            icon = m["i"].jStr() ?: FeatureStyle.DEFAULT_ICON,
            iconScale = m["s"].jNum()?.toFloat() ?: 1f,
        )
    }

    fun styleToJson(s: FeatureStyle) = Json.write(styleToMap(s))
    fun styleFromJson(s: String?) = styleFromMap(s?.let { runCatching { Json.parse(it).jObj() }.getOrNull() })

    fun attrsToJson(a: Map<String, String>) = Json.write(a)
    fun attrsFromJson(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val m = Json.parse(s).jObj() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in m) out[k] = v.jStr() ?: ""
        return out
    }

    fun listToJson(l: List<String>) = Json.write(l)
    fun listFromJson(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList() else Json.parse(s).jArr()?.mapNotNull { it.jStr() } ?: emptyList()

    fun formToJson(f: List<FormField>) = Json.write(f.map {
        mapOf("n" to it.name, "t" to it.type.name, "o" to it.options, "r" to it.required)
    })

    fun formFromJson(s: String?): List<FormField> {
        if (s.isNullOrBlank()) return emptyList()
        return Json.parse(s).jArr()?.mapNotNull { o ->
            val m = o.jObj() ?: return@mapNotNull null
            FormField(
                name = m["n"].jStr() ?: return@mapNotNull null,
                type = runCatching { FieldType.valueOf(m["t"].jStr() ?: "TEXT") }.getOrDefault(FieldType.TEXT),
                options = m["o"].jArr()?.mapNotNull { it.jStr() } ?: emptyList(),
                required = m["r"] == true,
            )
        } ?: emptyList()
    }

    /** Elemento completo (para backup/histórico). */
    fun featureToMap(f: GeoFeature): Map<String, Any?> = linkedMapOf(
        "name" to f.name, "desc" to f.description, "cat" to f.category,
        "geom" to geomToMap(f.geom), "style" to styleToMap(f.style), "attrs" to f.attrs,
        "photos" to f.photos, "folder" to f.folder, "ele" to f.eleSource, "rec" to f.recorded,
        "created" to f.created, "updated" to f.updated,
    )

    fun featureFromMap(m: Map<String, Any?>, projectId: Long = 0): GeoFeature = GeoFeature(
        projectId = projectId,
        name = m["name"].jStr() ?: "",
        description = m["desc"].jStr() ?: "",
        category = m["cat"].jStr() ?: "",
        geom = geomFromMap(m["geom"].jObj()!!),
        style = styleFromMap(m["style"].jObj()),
        attrs = m["attrs"].jObj()?.mapValues { it.value.jStr() ?: "" } ?: emptyMap(),
        photos = m["photos"].jArr()?.mapNotNull { it.jStr() } ?: emptyList(),
        folder = m["folder"].jStr() ?: "",
        eleSource = m["ele"].jStr(),
        recorded = m["rec"] == true,
        created = (m["created"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        updated = (m["updated"] as? Number)?.toLong() ?: System.currentTimeMillis(),
    )
}
