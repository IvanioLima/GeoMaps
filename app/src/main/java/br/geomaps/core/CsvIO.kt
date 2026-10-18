package br.geomaps.core

import java.util.Locale

/** CSV de pontos (separador ";" e UTF-8 com BOM, compatível com o Excel em português). */
object CsvIO {

    fun write(features: List<GeoFeature>, prefs: CoordPrefs): ByteArray {
        val pts = features.filter { it.geom is Geom.Pt }
        val keys = LinkedHashSet<String>()
        pts.forEach { keys.addAll(it.attrs.keys) }
        val sb = StringBuilder("﻿")
        val header = listOf("nome", "descricao", "categoria", "latitude", "longitude", "altitude", "utm_fuso", "utm_e", "utm_n") + keys
        sb.append(header.joinToString(";") { q(it) }).append("\r\n")
        for (f in pts) {
            val c = (f.geom as Geom.Pt).c
            val u = CoordFormat.toUtm(c.lat, c.lon, prefs)
            val row = listOf(
                f.name, f.description, f.category,
                String.format(Locale.US, "%.8f", c.lat), String.format(Locale.US, "%.8f", c.lon),
                c.ele?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                "${u.zone}${u.hemisphere}", String.format(Locale.US, "%.3f", u.e), String.format(Locale.US, "%.3f", u.n),
            ) + keys.map { f.attrs[it] ?: "" }
            sb.append(row.joinToString(";") { q(it) }).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun q(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' || it == ',' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun parseLine(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                } else sb.append(ch)
            } else when (ch) {
                '"' -> inQ = true
                sep -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    fun read(bytes: ByteArray, sourceName: String, prefs: CoordPrefs): ImportResult {
        var text = bytes.toString(Charsets.UTF_8)
        if (text.contains('�')) text = bytes.toString(Charsets.ISO_8859_1)
        text = text.removePrefix("﻿")
        val lines = text.split(Regex("\r\n|\n|\r")).filter { it.isNotBlank() }
        if (lines.size < 2) throw GeoFormatException("CSV vazio")
        val first = lines[0]
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> first.count { it == c } } ?: ';'
        val head = parseLine(first, sep).map { it.trim() }
        val lower = head.map { it.lowercase(Locale.ROOT) }
        fun col(vararg n: String) = lower.indexOfFirst { it in n }
        val iLat = col("lat", "latitude", "y", "lat_dd")
        val iLon = col("lon", "lng", "long", "longitude", "x", "lon_dd")
        val iE = col("e", "este", "easting", "utm_e", "x_utm", "coord_e")
        val iN = col("n", "norte", "northing", "utm_n", "y_utm", "coord_n")
        val iZ = col("zona", "zone", "fuso", "utm_fuso", "utm_zone")
        val iName = col("nome", "name", "ponto", "id", "label")
        val iDesc = col("descricao", "descrição", "description", "desc", "obs")
        val iCat = col("categoria", "category", "tipo", "type")
        val iEle = col("altitude", "alt", "ele", "elevation", "cota", "z")
        val useGeo = iLat >= 0 && iLon >= 0
        if (!useGeo && (iE < 0 || iN < 0)) throw GeoFormatException("CSV sem colunas de coordenadas (lat/lon ou E/N)")
        val skip = setOf(iLat, iLon, iE, iN, iZ, iName, iDesc, iCat, iEle, col("utm_fuso"))
        val out = ArrayList<GeoFeature>()
        val warnings = ArrayList<String>()
        for ((ln, l) in lines.drop(1).withIndex()) {
            val v = parseLine(l, sep)
            fun g(i: Int) = if (i >= 0) v.getOrNull(i)?.trim() ?: "" else ""
            val coord: Pair<Double, Double>? = if (useGeo) {
                val a = CoordFormat.num(g(iLat)); val b = CoordFormat.num(g(iLon))
                if (a != null && b != null) a to b else CoordFormat.parse(g(iLat) + " " + g(iLon), prefs)
            } else {
                val e = CoordFormat.num(g(iE)); val n = CoordFormat.num(g(iN))
                if (e == null || n == null) null else {
                    val zs = g(iZ).uppercase(Locale.ROOT)
                    val zone = Regex("\\d+").find(zs)?.value?.toIntOrNull() ?: prefs.fixedZone
                    val south = if (zs.contains('N')) false else if (zs.contains('S')) true else prefs.fixedSouth
                    if (zone == null) null else Utm.toLatLon(UtmCoord(zone, south, e, n), prefs.datum.ellipsoid)
                }
            }
            if (coord == null) { warnings += "Linha ${ln + 2}: coordenada inválida"; continue }
            val attrs = LinkedHashMap<String, String>()
            head.forEachIndexed { i, h -> if (i !in skip && h.isNotEmpty()) attrs[h] = g(i) }
            out += GeoFeature(
                name = g(iName).ifEmpty { "Ponto ${ln + 1}" }, description = g(iDesc), category = g(iCat),
                geom = Geom.Pt(Coord(coord.first, coord.second, CoordFormat.num(g(iEle)))), attrs = attrs,
            )
        }
        if (!useGeo && iZ < 0 && prefs.fixedZone == null) warnings += "CSV em UTM sem coluna de fuso: defina um fuso fixo nas configurações"
        if (out.isEmpty()) throw GeoFormatException("Nenhum ponto válido no CSV")
        return ImportResult(sourceName, out, emptyMap(), warnings)
    }
}
