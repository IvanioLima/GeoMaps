package br.geomaps.core

import org.w3c.dom.Element
import java.util.Locale

object Gpx {

    fun read(bytes: ByteArray, sourceName: String): ImportResult {
        val doc = runCatching { Xml.parse(bytes) }.getOrElse { throw GeoFormatException("GPX inválido: ${it.message}", it) }
        val root = doc.documentElement
        if (!Xml.local(root).equals("gpx", true)) throw GeoFormatException("O arquivo não é um GPX")
        val out = ArrayList<GeoFeature>()
        val base = sourceName.substringBeforeLast('.')

        for (w in Xml.childrenNamed(root, "wpt")) {
            val c = pt(w) ?: continue
            out += GeoFeature(
                name = Xml.childText(w, "name") ?: "Ponto ${out.size + 1}",
                description = Xml.childText(w, "desc") ?: Xml.childText(w, "cmt") ?: "",
                category = Xml.childText(w, "type") ?: "",
                geom = Geom.Pt(c),
                attrs = buildMap {
                    Xml.childText(w, "sym")?.let { put("sym", it) }
                    Xml.childText(w, "cmt")?.let { if (it != Xml.childText(w, "desc")) put("comentário", it) }
                },
                folder = "Pontos",
            )
        }
        for ((ri, r) in Xml.childrenNamed(root, "rte").withIndex()) {
            val pts = Xml.childrenNamed(r, "rtept").mapNotNull { pt(it) }
            if (pts.size < 2) continue
            out += GeoFeature(
                name = Xml.childText(r, "name") ?: "Rota ${ri + 1}",
                description = Xml.childText(r, "desc") ?: "",
                category = Xml.childText(r, "type") ?: "",
                geom = Geom.Line.of(pts),
                style = FeatureStyle(color = 0xFF1E88E5.toInt()),
                folder = "Rotas",
            )
        }
        for ((ti, t) in Xml.childrenNamed(root, "trk").withIndex()) {
            val parts = Xml.childrenNamed(t, "trkseg").map { s -> Xml.childrenNamed(s, "trkpt").mapNotNull { pt(it) } }.filter { it.size >= 2 }
            if (parts.isEmpty()) continue
            out += GeoFeature(
                name = Xml.childText(t, "name") ?: "Trilha ${ti + 1}",
                description = Xml.childText(t, "desc") ?: "",
                category = Xml.childText(t, "type") ?: "",
                geom = Geom.Line(parts),
                style = FeatureStyle(color = gpxColor(t) ?: FeatureStyle.DEFAULT_LINE),
                folder = "Trilhas",
                recorded = parts.any { p -> p.any { it.time != null } },
            )
        }
        if (out.isEmpty()) throw GeoFormatException("Nenhum ponto, rota ou trilha encontrado em $base")
        return ImportResult(sourceName, out)
    }

    private fun gpxColor(t: Element): Int? {
        // Extensão Garmin/gpx_style: <extensions><gpx_style:line><gpx_style:color>FF0000
        val c = Xml.descendants(t, "color").firstOrNull()?.textContent?.trim()?.removePrefix("#") ?: return null
        val v = c.toLongOrNull(16) ?: return null
        return if (c.length == 6) (0xFF000000 or v).toInt() else null
    }

    private fun pt(e: Element): Coord? {
        val lat = e.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = e.getAttribute("lon").toDoubleOrNull() ?: return null
        val acc = Xml.childText(e, "hdop")?.toDoubleOrNull()?.let { it * 5 }
        return Coord(lat, lon, Xml.childText(e, "ele")?.toDoubleOrNull(), Xml.parseTime(Xml.childText(e, "time")), acc)
    }

    /** Polígonos são exportados como trilhas fechadas (o GPX não tem polígonos). */
    fun write(features: List<GeoFeature>, name: String): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="GeoMaps" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""").append('\n')
        sb.append("<metadata><name>").append(Xml.esc(name)).append("</name></metadata>\n")
        for (f in features) {
            val g = f.geom
            if (g is Geom.Pt) {
                sb.append("<wpt").append(latLon(g.c)).append('>')
                g.c.ele?.let { sb.append("<ele>").append(fmt(it, 2)).append("</ele>") }
                g.c.time?.let { sb.append("<time>").append(Xml.formatTime(it)).append("</time>") }
                sb.append("<name>").append(Xml.esc(f.name)).append("</name>")
                if (f.description.isNotEmpty()) sb.append("<desc>").append(Xml.esc(f.description)).append("</desc>")
                f.attrs["sym"]?.let { sb.append("<sym>").append(Xml.esc(it)).append("</sym>") }
                if (f.category.isNotEmpty()) sb.append("<type>").append(Xml.esc(f.category)).append("</type>")
                sb.append("</wpt>\n")
            }
        }
        for (f in features) {
            val parts: List<List<Coord>> = when (val g = f.geom) {
                is Geom.Line -> g.parts
                is Geom.Poly -> g.polys.map { p -> p.first().let { r -> if (r.isNotEmpty()) r + r.first() else r } }
                else -> continue
            }
            sb.append("<trk><name>").append(Xml.esc(f.name)).append("</name>")
            if (f.description.isNotEmpty()) sb.append("<desc>").append(Xml.esc(f.description)).append("</desc>")
            if (f.category.isNotEmpty()) sb.append("<type>").append(Xml.esc(f.category)).append("</type>")
            sb.append("\n")
            for (p in parts) {
                sb.append("<trkseg>\n")
                for (c in p) {
                    sb.append("<trkpt").append(latLon(c)).append('>')
                    c.ele?.let { sb.append("<ele>").append(fmt(it, 2)).append("</ele>") }
                    c.time?.let { sb.append("<time>").append(Xml.formatTime(it)).append("</time>") }
                    sb.append("</trkpt>\n")
                }
                sb.append("</trkseg>\n")
            }
            sb.append("</trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun latLon(c: Coord) = " lat=\"${fmt(c.lat, 8)}\" lon=\"${fmt(c.lon, 8)}\""
    private fun fmt(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)
}
