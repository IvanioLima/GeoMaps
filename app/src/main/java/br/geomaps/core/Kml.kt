package br.geomaps.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Leitura e escrita de KML e KMZ preservando estilos (ícones, cores, espessuras) e atributos. */
object Kml {

    private data class KStyle(
        var iconHref: String? = null,
        var iconColor: Int? = null,
        var iconScale: Float? = null,
        var lineColor: Int? = null,
        var lineWidth: Float? = null,
        var polyColor: Int? = null,
        var polyFill: Boolean? = null,
        var polyOutline: Boolean? = null,
    ) {
        fun merge(o: KStyle?): KStyle {
            if (o == null) return this
            return KStyle(
                o.iconHref ?: iconHref, o.iconColor ?: iconColor, o.iconScale ?: iconScale,
                o.lineColor ?: lineColor, o.lineWidth ?: lineWidth, o.polyColor ?: polyColor,
                o.polyFill ?: polyFill, o.polyOutline ?: polyOutline,
            )
        }
    }

    /** KML "aabbggrr" → ARGB */
    fun kmlColorToArgb(s: String?): Int? {
        val t = s?.trim()?.removePrefix("#") ?: return null
        if (t.length != 8) return null
        val v = t.toLongOrNull(16) ?: return null
        val a = (v shr 24) and 0xFF; val b = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val r = v and 0xFF
        return ((a shl 24) or (r shl 16) or (g shl 8) or b).toInt()
    }

    fun argbToKml(c: Int): String {
        val a = (c ushr 24) and 0xFF; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return String.format("%02x%02x%02x%02x", a, b, g, r)
    }

    // ---------------------------------------------------------------- leitura

    fun readKmz(bytes: ByteArray, sourceName: String): ImportResult {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) entries[e.name.replace('\\', '/')] = zis.readBytes()
            }
        }
        val kmlName = entries.keys.firstOrNull { it.equals("doc.kml", true) }
            ?: entries.keys.filter { it.endsWith(".kml", true) }.minByOrNull { it.count { c -> c == '/' } }
            ?: throw GeoFormatException("KMZ sem arquivo KML interno")
        val dir = kmlName.substringBeforeLast('/', "")
        return read(entries[kmlName]!!, sourceName) { href ->
            val clean = href.removePrefix("./")
            val cand = listOf(if (dir.isEmpty()) clean else "$dir/$clean", clean)
            cand.firstNotNullOfOrNull { c -> entries[c] ?: entries.entries.firstOrNull { it.key.equals(c, true) }?.value }
        }
    }

    fun read(bytes: ByteArray, sourceName: String, resolver: ((String) -> ByteArray?)? = null): ImportResult {
        val doc = runCatching { Xml.parse(bytes) }.getOrElse { throw GeoFormatException("KML inválido: ${it.message}", it) }
        val root = doc.documentElement
        val styles = HashMap<String, KStyle>()
        val styleMaps = HashMap<String, String>()
        for (s in Xml.descendants(root, "Style")) {
            val id = s.getAttribute("id")
            if (id.isNotEmpty()) styles[id] = parseStyle(s)
        }
        for (sm in Xml.descendants(root, "StyleMap")) {
            val id = sm.getAttribute("id")
            if (id.isEmpty()) continue
            val pair = Xml.childrenNamed(sm, "Pair").firstOrNull { Xml.childText(it, "key") == "normal" } ?: Xml.childrenNamed(sm, "Pair").firstOrNull()
            val url = pair?.let { Xml.childText(it, "styleUrl") } ?: continue
            styleMaps[id] = url.substringAfter('#')
        }

        fun resolveStyle(url: String?): KStyle? {
            if (url == null) return null
            var id = url.substringAfter('#')
            var guard = 0
            while (styleMaps.containsKey(id) && guard++ < 5) id = styleMaps[id]!!
            return styles[id]
        }

        val out = ArrayList<GeoFeature>()
        val resources = HashMap<String, ByteArray>()
        val warnings = ArrayList<String>()

        fun walk(e: Element, folder: String) {
            for (c in Xml.children(e)) {
                when (Xml.local(c)) {
                    "Folder", "Document" -> {
                        val n = Xml.childText(c, "name")
                        val sub = if (Xml.local(c) == "Document" && folder.isEmpty()) "" else listOfNotNull(folder.ifEmpty { null }, n ?: "Pasta").joinToString("/")
                        walk(c, sub)
                    }
                    "Placemark" -> runCatching { placemark(c, folder, ::resolveStyle, resolver, resources, out) }
                        .onFailure { warnings += "Elemento ignorado (${Xml.childText(c, "name") ?: "?"}): ${it.message}" }
                    "GroundOverlay", "NetworkLink", "ScreenOverlay", "PhotoOverlay" ->
                        warnings += "${Xml.local(c)} não suportado: ${Xml.childText(c, "name") ?: ""}"
                }
            }
        }
        walk(root, "")
        if (out.isEmpty()) throw GeoFormatException("Nenhum elemento vetorial encontrado no KML")
        return ImportResult(sourceName, out, resources, warnings.distinct())
    }

    private fun parseStyle(s: Element): KStyle {
        val st = KStyle()
        Xml.child(s, "IconStyle")?.let { ic ->
            st.iconColor = kmlColorToArgb(Xml.childText(ic, "color"))
            st.iconScale = Xml.childText(ic, "scale")?.toFloatOrNull()
            st.iconHref = Xml.child(ic, "Icon")?.let { Xml.childText(it, "href") }
        }
        Xml.child(s, "LineStyle")?.let { ls ->
            st.lineColor = kmlColorToArgb(Xml.childText(ls, "color"))
            st.lineWidth = Xml.childText(ls, "width")?.toFloatOrNull()
        }
        Xml.child(s, "PolyStyle")?.let { ps ->
            st.polyColor = kmlColorToArgb(Xml.childText(ps, "color"))
            st.polyFill = Xml.childText(ps, "fill")?.let { it != "0" }
            st.polyOutline = Xml.childText(ps, "outline")?.let { it != "0" }
        }
        return st
    }

    private fun coords(text: String?): List<Coord> {
        if (text.isNullOrBlank()) return emptyList()
        return text.trim().split(Regex("\\s+")).mapNotNull { tup ->
            val p = tup.split(',')
            if (p.size < 2) return@mapNotNull null
            val lon = p[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
            Coord(lat, lon, p.getOrNull(2)?.toDoubleOrNull()?.takeIf { it != 0.0 })
        }
    }

    private fun placemark(
        pm: Element, folder: String, resolveStyle: (String?) -> KStyle?,
        resolver: ((String) -> ByteArray?)?, resources: HashMap<String, ByteArray>, out: MutableList<GeoFeature>,
    ) {
        val name = Xml.childText(pm, "name") ?: ""
        var desc = Xml.childText(pm, "description") ?: ""
        var st = KStyle()
        st = st.merge(resolveStyle(Xml.childText(pm, "styleUrl")))
        Xml.child(pm, "Style")?.let { st = st.merge(parseStyle(it)) }
        // StyleMap embutido
        Xml.child(pm, "StyleMap")?.let { sm ->
            val p = Xml.childrenNamed(sm, "Pair").firstOrNull { Xml.childText(it, "key") == "normal" }
            if (p != null) {
                val inner = Xml.child(p, "Style")
                st = if (inner != null) st.merge(parseStyle(inner)) else st.merge(resolveStyle(Xml.childText(p, "styleUrl")))
            }
        }

        val attrs = LinkedHashMap<String, String>()
        var category = ""
        Xml.child(pm, "ExtendedData")?.let { ed ->
            for (d in Xml.childrenNamed(ed, "Data")) {
                val k = d.getAttribute("name").takeIf { it.isNotEmpty() } ?: Xml.childText(d, "displayName") ?: continue
                val v = Xml.childText(d, "value") ?: ""
                if (k.equals("categoria", true) || k.equals("category", true)) category = v else attrs[k] = v
            }
            for (sd in Xml.descendants(ed, "SimpleData")) {
                val k = sd.getAttribute("name")
                if (k.isNotEmpty()) attrs[k] = sd.textContent.trim()
            }
        }
        Xml.childText(pm, "address")?.let { attrs["endereço"] = it }

        // Fotos referenciadas na descrição (<img src="files/foto.jpg">) dentro de KMZ
        val photos = ArrayList<String>()
        if (resolver != null) {
            Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(desc).forEach { m ->
                val src = m.groupValues[1]
                if (!src.startsWith("http", true)) resolver(src)?.let { b ->
                    resources["photo:$src"] = b
                    photos += "href:$src"
                }
            }
            if (photos.isNotEmpty()) desc = desc.replace(Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE), "").trim()
        }

        var iconKey = FeatureStyle.DEFAULT_ICON
        st.iconHref?.let { href ->
            iconKey = "href:$href"
            if (resolver != null && !href.startsWith("http", true)) resolver(href)?.let { resources["href:$href"] = it }
        }

        fun style(t: GeomType): FeatureStyle = when (t) {
            GeomType.POINT -> FeatureStyle(
                color = st.iconColor ?: if (st.iconHref != null) 0xFFFFFFFF.toInt() else FeatureStyle.DEFAULT_LINE,
                icon = iconKey, iconScale = st.iconScale ?: 1f,
            )
            GeomType.LINE -> FeatureStyle(color = st.lineColor ?: FeatureStyle.DEFAULT_LINE, width = st.lineWidth ?: 3f)
            GeomType.POLYGON -> FeatureStyle(
                color = if (st.polyOutline == false) 0 else (st.lineColor ?: FeatureStyle.DEFAULT_LINE),
                width = st.lineWidth ?: 2f,
                fill = if (st.polyFill == false) 0 else (st.polyColor ?: FeatureStyle.DEFAULT_FILL),
            )
        }

        val geoms = ArrayList<Geom>()
        fun collect(e: Element) {
            for (g in Xml.children(e)) {
                when (Xml.local(g)) {
                    "Point" -> coords(Xml.childText(g, "coordinates")).firstOrNull()?.let { geoms += Geom.Pt(it) }
                    "LineString" -> coords(Xml.childText(g, "coordinates")).takeIf { it.size >= 2 }?.let { geoms += Geom.Line.of(it) }
                    "LinearRing" -> coords(Xml.childText(g, "coordinates")).takeIf { it.size >= 3 }?.let { geoms += Geom.Poly.of(it) }
                    "Polygon" -> {
                        val outer = Xml.child(g, "outerBoundaryIs")?.let { Xml.child(it, "LinearRing") }?.let { coords(Xml.childText(it, "coordinates")) }
                        if (outer != null && outer.size >= 3) {
                            val holes = Xml.childrenNamed(g, "innerBoundaryIs").mapNotNull { ib ->
                                Xml.child(ib, "LinearRing")?.let { coords(Xml.childText(it, "coordinates")) }?.takeIf { it.size >= 3 }
                            }
                            geoms += Geom.Poly(listOf((listOf(outer) + holes).map { Geom.Poly.openRing(it) }))
                        }
                    }
                    "MultiGeometry" -> collect(g)
                    "Track" -> track(g)?.let { geoms += Geom.Line.of(it) }
                    "MultiTrack" -> {
                        val parts = Xml.childrenNamed(g, "Track").mapNotNull { track(it) }
                        if (parts.isNotEmpty()) geoms += Geom.Line(parts)
                    }
                }
            }
        }
        collect(pm)
        if (geoms.isEmpty()) return

        // Agrupa geometrias do mesmo tipo (MultiGeometry) num só elemento
        val lines = geoms.filterIsInstance<Geom.Line>()
        val polys = geoms.filterIsInstance<Geom.Poly>()
        val pts = geoms.filterIsInstance<Geom.Pt>()
        val grouped = ArrayList<Geom>()
        if (lines.isNotEmpty()) grouped += Geom.Line(lines.flatMap { it.parts })
        if (polys.isNotEmpty()) grouped += Geom.Poly(polys.flatMap { it.polys })
        grouped += pts
        val defaultName = name
        grouped.forEachIndexed { i, g ->
            out += GeoFeature(
                name = if (grouped.size > 1 && i > 0) "$defaultName (${i + 1})" else defaultName,
                description = desc, category = category, geom = g, style = style(g.type),
                attrs = attrs, photos = photos, folder = folder,
                recorded = g is Geom.Line && g.parts.any { p -> p.any { it.time != null } },
            )
        }
    }

    private fun track(t: Element): List<Coord>? {
        val whens = Xml.childrenNamed(t, "when").map { Xml.parseTime(it.textContent) }
        val cs = Xml.childrenNamed(t, "coord").mapIndexedNotNull { i, c ->
            val p = c.textContent.trim().split(Regex("\\s+"))
            val lon = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapIndexedNotNull null
            Coord(lat, lon, p.getOrNull(2)?.toDoubleOrNull(), whens.getOrNull(i))
        }
        return cs.takeIf { it.size >= 2 }
    }

    // ---------------------------------------------------------------- escrita

    /** Fornece o PNG de um ícone (para KMZ) a partir da chave de estilo e cor. */
    fun interface IconProvider {
        fun png(iconKey: String, color: Int): ByteArray?
    }

    fun interface PhotoProvider {
        fun bytes(photo: String): ByteArray?
    }

    fun writeKml(features: List<GeoFeature>, docName: String): ByteArray =
        build(features, docName, null, null, null).toByteArray(Charsets.UTF_8)

    fun writeKmz(features: List<GeoFeature>, docName: String, icons: IconProvider, photos: PhotoProvider): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        val kml = build(features, docName, icons, photos, files)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray(Charsets.UTF_8)); z.closeEntry()
            for ((n, b) in files) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun build(
        features: List<GeoFeature>, docName: String, icons: IconProvider?, photos: PhotoProvider?, files: MutableMap<String, ByteArray>?,
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">""").append('\n')
        sb.append("<Document>\n<name>").append(Xml.esc(docName)).append("</name>\n")

        // Estilos compartilhados
        val styleIds = LinkedHashMap<String, String>()
        val iconFiles = HashMap<String, String>()
        fun styleKey(f: GeoFeature) = "${f.type}|${f.style.color}|${f.style.width}|${f.style.fill}|${f.style.icon}|${f.style.iconScale}"
        for (f in features) {
            val k = styleKey(f)
            if (styleIds.containsKey(k)) continue
            val id = "s${styleIds.size + 1}"
            styleIds[k] = id
            sb.append("<Style id=\"").append(id).append("\">")
            val s = f.style
            when (f.type) {
                GeomType.POINT -> {
                    var href = "http://maps.google.com/mapfiles/kml/paddle/wht-blank.png"
                    var color = s.color
                    if (icons != null && files != null) {
                        val ik = "${s.icon}|${s.color}"
                        val fname = iconFiles.getOrPut(ik) {
                            val png = icons.png(s.icon, s.color)
                            if (png != null) {
                                val n = "files/icons/icon${iconFiles.size + 1}.png"
                                files[n] = png; n
                            } else ""
                        }
                        if (fname.isNotEmpty()) { href = fname; color = 0xFFFFFFFF.toInt() }
                    } else if (s.icon.startsWith("href:http")) {
                        href = s.icon.removePrefix("href:")
                    }
                    sb.append("<IconStyle><color>").append(argbToKml(color)).append("</color><scale>")
                        .append(String.format(Locale.US, "%.2f", s.iconScale)).append("</scale><Icon><href>")
                        .append(Xml.esc(href)).append("</href></Icon></IconStyle>")
                    sb.append("<LabelStyle><scale>0.9</scale></LabelStyle>")
                }
                GeomType.LINE -> sb.append("<LineStyle><color>").append(argbToKml(s.color)).append("</color><width>")
                    .append(String.format(Locale.US, "%.1f", s.width)).append("</width></LineStyle>")
                GeomType.POLYGON -> {
                    sb.append("<LineStyle><color>").append(argbToKml(s.color)).append("</color><width>")
                        .append(String.format(Locale.US, "%.1f", s.width)).append("</width></LineStyle>")
                    sb.append("<PolyStyle><color>").append(argbToKml(s.fill)).append("</color>")
                    if ((s.fill ushr 24) == 0) sb.append("<fill>0</fill>")
                    if ((s.color ushr 24) == 0) sb.append("<outline>0</outline>")
                    sb.append("</PolyStyle>")
                }
            }
            sb.append("</Style>\n")
        }

        // Árvore de pastas
        class Node(val name: String) {
            val children = LinkedHashMap<String, Node>()
            val items = ArrayList<GeoFeature>()
        }
        val root = Node("")
        for (f in features) {
            var n = root
            if (f.folder.isNotBlank()) for (p in f.folder.split('/').filter { it.isNotBlank() }) n = n.children.getOrPut(p) { Node(p) }
            n.items += f
        }
        var photoN = 0
        fun placemark(f: GeoFeature) {
            sb.append("<Placemark><name>").append(Xml.esc(f.name)).append("</name>")
            var desc = f.description
            if (photos != null && files != null && f.photos.isNotEmpty()) {
                val imgs = StringBuilder()
                for (p in f.photos) {
                    val b = photos.bytes(p) ?: continue
                    val n = "files/photos/foto${++photoN}.${p.substringAfterLast('.', "jpg").take(4)}"
                    files[n] = b
                    imgs.append("<img src=\"").append(n).append("\" width=\"400\"/><br/>")
                }
                if (imgs.isNotEmpty()) desc = if (desc.isEmpty()) imgs.toString() else "$desc<br/>$imgs"
            }
            if (desc.isNotEmpty()) sb.append("<description><![CDATA[").append(desc.replace("]]>", "]] >")).append("]]></description>")
            sb.append("<styleUrl>#").append(styleIds[styleKey(f)]).append("</styleUrl>")
            if (f.category.isNotEmpty() || f.attrs.isNotEmpty()) {
                sb.append("<ExtendedData>")
                if (f.category.isNotEmpty()) sb.append("<Data name=\"categoria\"><value>").append(Xml.esc(f.category)).append("</value></Data>")
                for ((k, v) in f.attrs) sb.append("<Data name=\"").append(Xml.esc(k)).append("\"><value>").append(Xml.esc(v)).append("</value></Data>")
                sb.append("</ExtendedData>")
            }
            geometry(f.geom, sb)
            sb.append("</Placemark>\n")
        }
        fun writeNode(n: Node) {
            for (f in n.items) placemark(f)
            for (c in n.children.values) {
                sb.append("<Folder><name>").append(Xml.esc(c.name)).append("</name>\n")
                writeNode(c)
                sb.append("</Folder>\n")
            }
        }
        writeNode(root)
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    private fun coordText(c: List<Coord>, sb: StringBuilder) {
        sb.append("<coordinates>")
        for ((i, p) in c.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append(String.format(Locale.US, "%.8f,%.8f", p.lon, p.lat))
            p.ele?.let { sb.append(String.format(Locale.US, ",%.2f", it)) }
        }
        sb.append("</coordinates>")
    }

    private fun geometry(g: Geom, sb: StringBuilder) {
        when (g) {
            is Geom.Pt -> { sb.append("<Point>"); coordText(listOf(g.c), sb); sb.append("</Point>") }
            is Geom.Line -> {
                if (g.parts.size > 1) sb.append("<MultiGeometry>")
                for (p in g.parts) { sb.append("<LineString><tessellate>1</tessellate>"); coordText(p, sb); sb.append("</LineString>") }
                if (g.parts.size > 1) sb.append("</MultiGeometry>")
            }
            is Geom.Poly -> {
                if (g.polys.size > 1) sb.append("<MultiGeometry>")
                for (poly in g.polys) {
                    sb.append("<Polygon><tessellate>1</tessellate>")
                    poly.forEachIndexed { i, r ->
                        sb.append(if (i == 0) "<outerBoundaryIs><LinearRing>" else "<innerBoundaryIs><LinearRing>")
                        coordText(if (r.isNotEmpty()) r + r.first() else r, sb)
                        sb.append(if (i == 0) "</LinearRing></outerBoundaryIs>" else "</LinearRing></innerBoundaryIs>")
                    }
                    sb.append("</Polygon>")
                }
                if (g.polys.size > 1) sb.append("</MultiGeometry>")
            }
        }
    }
}
