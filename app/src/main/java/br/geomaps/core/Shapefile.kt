package br.geomaps.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Importação/exportação de Shapefile em .zip (.shp + .shx + .dbf + .prj + .cpg). */
object Shapefile {

    private val NAME_FIELDS = listOf("NOME", "NAME", "NM", "LABEL", "ROTULO", "RÓTULO", "TITULO", "DESCRICAO", "ID")
    private val DESC_FIELDS = listOf("DESCRICAO", "DESCRIÇÃO", "DESCRIPTIO", "DESCRIPTION", "DESC", "OBS")

    fun readZip(bytes: ByteArray, sourceName: String, encodingHint: String? = null): ImportResult {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && !e.name.contains("__MACOSX")) entries[e.name.replace('\\', '/')] = zis.readBytes()
            }
        }
        val shps = entries.keys.filter { it.endsWith(".shp", true) }
        if (shps.isEmpty()) throw GeoFormatException("O .zip não contém arquivo .shp")
        val out = ArrayList<GeoFeature>()
        val warnings = ArrayList<String>()
        for (shp in shps) {
            val base = shp.substring(0, shp.length - 4)
            fun sib(ext: String) = entries.entries.firstOrNull { it.key.equals("$base.$ext", true) }?.value
            val dbf = sib("dbf")
            if (dbf == null) warnings += "${base.substringAfterLast('/')}: .dbf ausente (sem atributos)"
            val prj = sib("prj")?.toString(Charsets.ISO_8859_1)
            if (prj == null) warnings += "${base.substringAfterLast('/')}: .prj ausente — assumindo coordenadas geográficas SIRGAS 2000/WGS84"
            val cpg = sib("cpg")?.toString(Charsets.ISO_8859_1)?.trim()
            val crs = Crs.parse(prj)
            out += read(entries[shp]!!, dbf, crs, cpg ?: encodingHint, base.substringAfterLast('/'), warnings)
        }
        if (out.isEmpty()) throw GeoFormatException("Nenhuma geometria válida no shapefile")
        return ImportResult(sourceName, out, emptyMap(), warnings)
    }

    private fun charsetFor(cpg: String?): Charset? {
        val c = cpg?.uppercase(Locale.ROOT)?.trim() ?: return null
        return when {
            c.contains("UTF") && c.contains("8") -> Charsets.UTF_8
            c == "1252" || c.contains("1252") -> Charset.forName("windows-1252")
            c.contains("8859") || c.contains("LATIN") -> Charsets.ISO_8859_1
            c == "850" -> runCatching { Charset.forName("IBM850") }.getOrNull()
            else -> runCatching { Charset.forName(c) }.getOrNull()
        }
    }

    data class DbfField(val name: String, val type: Char, val length: Int, val decimals: Int)

    fun readDbf(dbf: ByteArray, cpg: String?): Pair<List<DbfField>, List<Map<String, String>>> {
        val bb = ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN)
        val nRec = bb.getInt(4)
        val headerLen = bb.getShort(8).toInt() and 0xFFFF
        val recLen = bb.getShort(10).toInt() and 0xFFFF
        val ldid = dbf[29].toInt() and 0xFF
        val fields = ArrayList<DbfField>()
        var p = 32
        while (p + 32 <= headerLen && dbf[p] != 0x0D.toByte()) {
            val nameBytes = dbf.copyOfRange(p, p + 11)
            val nlen = nameBytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) 11 else it }
            val name = String(nameBytes, 0, nlen, Charsets.ISO_8859_1).trim()
            fields += DbfField(name, (dbf[p + 11].toInt() and 0xFF).toChar(), dbf[p + 16].toInt() and 0xFF, dbf[p + 17].toInt() and 0xFF)
            p += 32
        }
        var cs = charsetFor(cpg) ?: when (ldid) {
            0x57, 0x03 -> Charset.forName("windows-1252")
            0x02 -> runCatching { Charset.forName("IBM850") }.getOrNull()
            else -> null
        }
        // Sem indicação: tenta UTF-8 estrito, senão Latin-1
        val records = ArrayList<Map<String, String>>(nRec)
        if (cs == null) {
            val dec: CharsetDecoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            cs = if (runCatching { dec.decode(ByteBuffer.wrap(dbf, headerLen, (dbf.size - headerLen).coerceAtLeast(0))) }.isSuccess) Charsets.UTF_8 else Charsets.ISO_8859_1
        }
        for (r in 0 until nRec) {
            val off = headerLen + r * recLen
            if (off + recLen > dbf.size) break
            if (dbf[off] == '*'.code.toByte()) { records += emptyMap(); continue }
            var fo = off + 1
            val m = LinkedHashMap<String, String>()
            for (f in fields) {
                val raw = String(dbf, fo, f.length.coerceAtMost(dbf.size - fo), cs!!).trim().trimEnd('\u0000')
                m[f.name] = when (f.type) {
                    'N', 'F' -> raw.trim()
                    'D' -> if (raw.length == 8) "${raw.substring(6, 8)}/${raw.substring(4, 6)}/${raw.substring(0, 4)}" else raw
                    'L' -> when (raw.uppercase()) { "T", "Y", "S" -> "Sim"; "F", "N" -> "Não"; else -> "" }
                    else -> raw
                }
                fo += f.length
            }
            records += m
        }
        return fields to records
    }

    private fun read(shp: ByteArray, dbf: ByteArray?, crs: Crs, cpg: String?, layer: String, warnings: MutableList<String>): List<GeoFeature> {
        val (fields, records) = if (dbf != null) readDbf(dbf, cpg) else (emptyList<DbfField>() to emptyList())
        val nameField = NAME_FIELDS.firstNotNullOfOrNull { n -> fields.firstOrNull { it.name.equals(n, true) } }?.name
            ?: fields.firstOrNull { it.type == 'C' }?.name
        val descField = DESC_FIELDS.firstNotNullOfOrNull { n -> fields.firstOrNull { it.name.equals(n, true) && it.name != nameField } }?.name
        val catField = fields.firstOrNull { it.name.equals("CATEGORIA", true) || it.name.equals("CATEGORY", true) || it.name.equals("TIPO", true) }?.name

        val bb = ByteBuffer.wrap(shp)
        if (bb.order(ByteOrder.BIG_ENDIAN).getInt(0) != 9994) throw GeoFormatException("$layer.shp inválido")
        val fileLen = bb.order(ByteOrder.BIG_ENDIAN).getInt(24) * 2
        var pos = 100
        var idx = 0
        val out = ArrayList<GeoFeature>()
        fun xy(o: Int, z: Double?): Coord {
            val x = bb.order(ByteOrder.LITTLE_ENDIAN).getDouble(o)
            val y = bb.order(ByteOrder.LITTLE_ENDIAN).getDouble(o + 8)
            val (lat, lon) = crs.toGeographic(x, y)
            return Coord(lat, lon, z?.takeIf { !it.isNaN() && it > -1e38 && it != 0.0 })
        }
        var badCoords = 0
        while (pos + 8 <= minOf(fileLen, shp.size)) {
            val contentLen = bb.order(ByteOrder.BIG_ENDIAN).getInt(pos + 4) * 2
            val rec = pos + 8
            pos = rec + contentLen
            if (rec + 4 > shp.size) break
            val type = bb.order(ByteOrder.LITTLE_ENDIAN).getInt(rec)
            val attrs = records.getOrNull(idx) ?: emptyMap()
            idx++
            if (type == 0) continue
            val hasZ = type in listOf(11, 13, 15, 18)
            val geoms = ArrayList<Geom>()
            when (type) {
                1, 11, 21 -> {
                    val z = if (type == 11) bb.order(ByteOrder.LITTLE_ENDIAN).getDouble(rec + 20) else null
                    geoms += Geom.Pt(xy(rec + 4, z))
                }
                8, 18, 28 -> {
                    val n = bb.order(ByteOrder.LITTLE_ENDIAN).getInt(rec + 36)
                    val ptsOff = rec + 40
                    val zOff = ptsOff + 16 * n + 16
                    for (i in 0 until n) {
                        val z = if (hasZ) bb.order(ByteOrder.LITTLE_ENDIAN).getDouble(zOff + 8 * i) else null
                        geoms += Geom.Pt(xy(ptsOff + 16 * i, z))
                    }
                }
                3, 13, 23, 5, 15, 25 -> {
                    val nParts = bb.order(ByteOrder.LITTLE_ENDIAN).getInt(rec + 36)
                    val nPts = bb.order(ByteOrder.LITTLE_ENDIAN).getInt(rec + 40)
                    val partsOff = rec + 44
                    val ptsOff = partsOff + 4 * nParts
                    val zOff = ptsOff + 16 * nPts + 16
                    val starts = (0 until nParts).map { bb.order(ByteOrder.LITTLE_ENDIAN).getInt(partsOff + 4 * it) }
                    val parts = starts.mapIndexed { pi, st ->
                        val end = if (pi + 1 < nParts) starts[pi + 1] else nPts
                        (st until end).map { i ->
                            val z = if (hasZ && zOff + 8 * i + 8 <= rec + contentLen) bb.order(ByteOrder.LITTLE_ENDIAN).getDouble(zOff + 8 * i) else null
                            xy(ptsOff + 16 * i, z)
                        }
                    }
                    if (type == 3 || type == 13 || type == 23) {
                        val ok = parts.filter { it.size >= 2 }
                        if (ok.isNotEmpty()) geoms += Geom.Line(ok)
                    } else {
                        val poly = assemblePolygons(parts.map { Geom.Poly.openRing(it) }.filter { it.size >= 3 })
                        if (poly.polys.isNotEmpty()) geoms += poly
                    }
                }
                else -> { warnings += "$layer: tipo de geometria $type ignorado"; continue }
            }
            for (g in geoms) {
                if (g.allCoords().any { it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 }) { badCoords++; continue }
                val name = nameField?.let { attrs[it] }?.takeIf { it.isNotBlank() } ?: "$layer ${idx}"
                out += GeoFeature(
                    name = name,
                    description = descField?.let { attrs[it] } ?: "",
                    category = catField?.let { attrs[it] } ?: "",
                    geom = g,
                    attrs = attrs,
                    folder = layer,
                    style = when (g.type) {
                        GeomType.POINT -> FeatureStyle()
                        GeomType.LINE -> FeatureStyle(color = 0xFF1E88E5.toInt(), width = 3f)
                        GeomType.POLYGON -> FeatureStyle(color = 0xFFFB8C00.toInt(), width = 2f, fill = 0x44FB8C00)
                    },
                )
            }
        }
        if (badCoords > 0) warnings += "$layer: $badCoords elemento(s) com coordenadas fora do intervalo geográfico — verifique o .prj"
        return out
    }

    /** Anéis horários = externos; anti-horários = buracos (atribuídos ao externo que os contém). */
    private fun assemblePolygons(rings: List<List<Coord>>): Geom.Poly {
        val outers = ArrayList<MutableList<List<Coord>>>()
        val holes = ArrayList<List<Coord>>()
        for (r in rings) {
            if (GeoMath.signedAreaLonLat(r) <= 0) outers += mutableListOf(r) else holes += r
        }
        if (outers.isEmpty()) return Geom.Poly(holes.map { listOf(it) })
        for (h in holes) {
            val p = h.first()
            val owner = outers.firstOrNull { GeoMath.pointInRing(p.lat, p.lon, it[0]) } ?: outers[0]
            owner += h
        }
        return Geom.Poly(outers)
    }

    // ---------------------------------------------------------------- escrita

    /** Gera um .zip com um shapefile por tipo de geometria (pontos, linhas, polígonos). */
    fun writeZip(features: List<GeoFeature>, baseName: String, prjWkt: String = Crs.PRJ_SIRGAS2000): ByteArray {
        val bos = ByteArrayOutputStream()
        val safe = baseName.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifEmpty { "geomaps" }
        ZipOutputStream(bos).use { z ->
            for ((t, suffix) in listOf(GeomType.POINT to "pontos", GeomType.LINE to "linhas", GeomType.POLYGON to "areas")) {
                val fs = features.filter { it.type == t }
                if (fs.isEmpty()) continue
                val n = "${safe}_$suffix"
                val (shp, shx) = writeShp(fs, t)
                fun put(ext: String, b: ByteArray) { z.putNextEntry(ZipEntry("$n.$ext")); z.write(b); z.closeEntry() }
                put("shp", shp); put("shx", shx); put("dbf", writeDbf(fs)); put("prj", prjWkt.toByteArray()); put("cpg", "UTF-8".toByteArray())
            }
        }
        return bos.toByteArray()
    }

    private fun writeShp(fs: List<GeoFeature>, t: GeomType): Pair<ByteArray, ByteArray> {
        val shapeType = when (t) { GeomType.POINT -> 1; GeomType.LINE -> 3; GeomType.POLYGON -> 5 }
        val recs = fs.map { f -> record(f.geom, shapeType) }
        val all = fs.flatMap { it.geom.allCoords() }
        val b = GeoMath.bounds(all) ?: GeoMath.Bounds(0.0, 0.0, 0.0, 0.0)
        val shpLen = 100 + recs.sumOf { 8 + it.size }
        val shp = ByteBuffer.allocate(shpLen)
        val shx = ByteBuffer.allocate(100 + 8 * recs.size)
        header(shp, shpLen, shapeType, b)
        header(shx, 100 + 8 * recs.size, shapeType, b)
        var off = 100
        recs.forEachIndexed { i, r ->
            shp.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(r.size / 2)
            shp.put(r)
            shx.order(ByteOrder.BIG_ENDIAN).putInt(off / 2).putInt(r.size / 2)
            off += 8 + r.size
        }
        return shp.array() to shx.array()
    }

    private fun header(bb: ByteBuffer, len: Int, type: Int, b: GeoMath.Bounds) {
        bb.order(ByteOrder.BIG_ENDIAN).putInt(9994)
        repeat(5) { bb.putInt(0) }
        bb.putInt(len / 2)
        bb.order(ByteOrder.LITTLE_ENDIAN).putInt(1000).putInt(type)
        bb.putDouble(b.minLon).putDouble(b.minLat).putDouble(b.maxLon).putDouble(b.maxLat)
        repeat(4) { bb.putDouble(0.0) }
    }

    private fun record(g: Geom, type: Int): ByteArray {
        if (g is Geom.Pt) {
            val bb = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(1).putDouble(g.c.lon).putDouble(g.c.lat)
            return bb.array()
        }
        val parts: List<List<Coord>> = when (g) {
            is Geom.Line -> g.parts
            is Geom.Poly -> g.polys.flatMap { poly ->
                poly.mapIndexed { i, r ->
                    val closed = r + r.first()
                    val cw = GeoMath.signedAreaLonLat(closed) < 0
                    // externo horário, buracos anti-horários
                    if ((i == 0) != cw) closed.reversed() else closed
                }
            }
            else -> emptyList()
        }
        val nPts = parts.sumOf { it.size }
        val bb = ByteBuffer.allocate(44 + 4 * parts.size + 16 * nPts).order(ByteOrder.LITTLE_ENDIAN)
        val all = parts.flatten()
        val b = GeoMath.bounds(all)!!
        bb.putInt(type).putDouble(b.minLon).putDouble(b.minLat).putDouble(b.maxLon).putDouble(b.maxLat)
        bb.putInt(parts.size).putInt(nPts)
        var s = 0
        for (p in parts) { bb.putInt(s); s += p.size }
        for (c in all) bb.putDouble(c.lon).putDouble(c.lat)
        return bb.array()
    }

    private fun writeDbf(fs: List<GeoFeature>): ByteArray {
        val keys = LinkedHashSet<String>()
        fs.forEach { keys.addAll(it.attrs.keys) }
        val used = HashSet<String>()
        fun fname(s: String): String {
            var base = s.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_").take(10).ifEmpty { "CAMPO" }
            var n = base; var k = 1
            while (!used.add(n)) { val suf = "_${k++}"; n = base.take(10 - suf.length) + suf }
            return n
        }
        data class F(val name: String, val src: String?, val len: Int)
        val cols = ArrayList<F>()
        cols += F(fname("NOME"), "\u0000name", 100)
        cols += F(fname("DESCRICAO"), "\u0000desc", 254)
        cols += F(fname("CATEGORIA"), "\u0000cat", 60)
        cols += F(fname("ALTITUDE"), "\u0000ele", 20)
        for (k in keys) {
            if (k.equals("NOME", true) || k.equals("DESCRICAO", true) || k.equals("CATEGORIA", true)) continue
            val maxLen = fs.maxOf { (it.attrs[k] ?: "").toByteArray(Charsets.UTF_8).size }.coerceIn(10, 254)
            cols += F(fname(k), k, maxLen)
        }
        val recLen = 1 + cols.sumOf { it.len }
        val headerLen = 32 + 32 * cols.size + 1
        val bb = ByteBuffer.allocate(headerLen + recLen * fs.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(3); bb.put(126); bb.put(1); bb.put(1)
        bb.putInt(fs.size); bb.putShort(headerLen.toShort()); bb.putShort(recLen.toShort())
        repeat(17) { bb.put(0) }
        bb.put(0) // LDID: 0 — codificação indicada no .cpg (UTF-8)
        bb.put(0); bb.put(0)
        for (c in cols) {
            val nb = c.name.toByteArray(Charsets.US_ASCII)
            bb.put(nb); repeat(11 - nb.size) { bb.put(0) }
            bb.put('C'.code.toByte()); bb.putInt(0); bb.put(c.len.toByte()); bb.put(0)
            repeat(14) { bb.put(0) }
        }
        bb.put(0x0D)
        for (f in fs) {
            bb.put(' '.code.toByte())
            for (c in cols) {
                val v = when (c.src) {
                    "\u0000name" -> f.name
                    "\u0000desc" -> f.description
                    "\u0000cat" -> f.category
                    "\u0000ele" -> ((f.geom as? Geom.Pt)?.c?.ele)?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                    else -> f.attrs[c.src] ?: ""
                }
                val bytes = truncUtf8(v.replace('\n', ' '), c.len)
                bb.put(bytes)
                repeat(c.len - bytes.size) { bb.put(' '.code.toByte()) }
            }
        }
        bb.put(0x1A)
        return bb.array()
    }

    private fun truncUtf8(s: String, max: Int): ByteArray {
        var b = s.toByteArray(Charsets.UTF_8)
        if (b.size <= max) return b
        var str = s
        while (b.size > max && str.isNotEmpty()) { str = str.dropLast(1); b = str.toByteArray(Charsets.UTF_8) }
        return b
    }
}
