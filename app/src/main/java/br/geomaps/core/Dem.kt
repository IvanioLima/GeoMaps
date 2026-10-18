package br.geomaps.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.floor

/** Uma folha de modelo digital de elevação que sabe amostrar altitude num ponto. */
interface DemTile : AutoCloseable {
    val south: Double
    val west: Double
    val north: Double
    val east: Double
    fun covers(lat: Double, lon: Double) = lat in south..north && lon in west..east
    fun sample(lat: Double, lon: Double): Double?
}

/** Nomes padronizados das folhas 1°×1°. */
object DemNames {
    fun hgtName(latFloor: Int, lonFloor: Int): String {
        val ns = if (latFloor < 0) "S" else "N"
        val ew = if (lonFloor < 0) "W" else "E"
        return String.format("%s%02d%s%03d", ns, Math.abs(latFloor), ew, Math.abs(lonFloor))
    }

    /** URL do SRTM 1" (formato HGT compactado, repositório público "skadi" da AWS). */
    fun srtmUrl(latFloor: Int, lonFloor: Int): String {
        val n = hgtName(latFloor, lonFloor)
        return "https://s3.amazonaws.com/elevation-tiles-prod/skadi/${n.substring(0, 3)}/$n.hgt.gz"
    }

    fun copernicusName(latFloor: Int, lonFloor: Int): String {
        val ns = if (latFloor < 0) "S" else "N"
        val ew = if (lonFloor < 0) "W" else "E"
        return String.format("Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM", ns, Math.abs(latFloor), ew, Math.abs(lonFloor))
    }

    /** URL do Copernicus GLO-30 (GeoTIFF COG, repositório público na AWS). */
    fun copernicusUrl(latFloor: Int, lonFloor: Int): String {
        val n = copernicusName(latFloor, lonFloor)
        return "https://copernicus-dem-30m.s3.amazonaws.com/$n/$n.tif"
    }

    fun tilesFor(b: GeoMath.Bounds): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (la in floor(b.minLat).toInt()..floor(b.maxLat).toInt())
            for (lo in floor(b.minLon).toInt()..floor(b.maxLon).toInt()) out += la to lo
        return out
    }
}

/** Folha SRTM .hgt (inteiros 16 bits big-endian, 1201 ou 3601 amostras por lado). */
class HgtTile(file: File, latFloor: Int, lonFloor: Int) : DemTile {
    private val raf = RandomAccessFile(file, "r")
    private val size: Int
    override val south = latFloor.toDouble()
    override val west = lonFloor.toDouble()
    override val north = south + 1
    override val east = west + 1
    private val rowCache = object : LinkedHashMap<Int, ShortArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ShortArray>?) = size > 256
    }

    init {
        val n = Math.sqrt(raf.length() / 2.0).toInt()
        if (n * n * 2L != raf.length()) throw GeoFormatException("Arquivo HGT com tamanho inesperado")
        size = n
    }

    @Synchronized
    private fun row(r: Int): ShortArray = rowCache.getOrPut(r) {
        val bytes = ByteArray(size * 2)
        raf.seek(r.toLong() * size * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
        ShortArray(size).also { sb.get(it) }
    }

    private fun v(r: Int, c: Int): Double? {
        val s = row(r.coerceIn(0, size - 1))[c.coerceIn(0, size - 1)]
        return if (s.toInt() == -32768) null else s.toDouble()
    }

    override fun sample(lat: Double, lon: Double): Double? {
        val y = (north - lat) * (size - 1)
        val x = (lon - west) * (size - 1)
        return bilinear(x, y) { c, r -> v(r, c) }
    }

    override fun close() = raf.close()
}

internal inline fun bilinear(x: Double, y: Double, get: (Int, Int) -> Double?): Double? {
    val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
    val fx = x - x0; val fy = y - y0
    val a = get(x0, y0); val b = get(x0 + 1, y0); val c = get(x0, y0 + 1); val d = get(x0 + 1, y0 + 1)
    if (a == null || b == null || c == null || d == null) return listOfNotNull(a, b, c, d).takeIf { it.isNotEmpty() }?.average()
    return a * (1 - fx) * (1 - fy) + b * fx * (1 - fy) + c * (1 - fx) * fy + d * fx * fy
}

/**
 * Leitor de GeoTIFF (inclusive COG) para MDE: blocos em tiles ou faixas, sem compressão, DEFLATE ou LZW,
 * preditores 1/2/3, amostras float32/int16/int32. Lê somente os blocos necessários (com cache).
 */
class GeoTiffTile(file: File) : DemTile {
    private val raf = RandomAccessFile(file, "r")
    private val order: ByteOrder
    val width: Int
    val height: Int
    private val bitsPerSample: Int
    private val sampleFormat: Int
    private val compression: Int
    private val predictor: Int
    private val tiled: Boolean
    private val blockW: Int
    private val blockH: Int
    private val offsets: LongArray
    private val counts: LongArray
    private val noData: Double?
    private val x0: Double
    private val y0: Double
    private val sx: Double
    private val sy: Double
    override val south: Double
    override val west: Double
    override val north: Double
    override val east: Double
    private val asciiTags = HashMap<Int, String>()
    private val blockCache = object : LinkedHashMap<Int, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FloatArray>?) = size > 12
    }

    init {
        val h = ByteArray(8)
        raf.seek(0); raf.readFully(h)
        order = when {
            h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw GeoFormatException("Não é um TIFF")
        }
        val hb = ByteBuffer.wrap(h).order(order)
        if (hb.getShort(2).toInt() != 42) throw GeoFormatException("BigTIFF não suportado")
        val ifd = hb.getInt(4).toLong() and 0xFFFFFFFFL
        val tags = readIfd(ifd)
        fun one(t: Int, def: Long? = null): Long = tags[t]?.firstOrNull()?.toLong() ?: def ?: throw GeoFormatException("TIFF sem tag $t")
        width = one(256).toInt()
        height = one(257).toInt()
        bitsPerSample = one(258, 16).toInt()
        compression = one(259, 1).toInt()
        predictor = one(317, 1).toInt()
        sampleFormat = one(339, 1).toInt()
        tiled = tags.containsKey(322)
        if (tiled) {
            blockW = one(322).toInt(); blockH = one(323).toInt()
            offsets = tags[324]!!.map { it.toLong() }.toLongArray(); counts = tags[325]!!.map { it.toLong() }.toLongArray()
        } else {
            blockW = width; blockH = one(278, height.toLong()).toInt().coerceAtMost(height)
            offsets = tags[273]!!.map { it.toLong() }.toLongArray(); counts = tags[279]!!.map { it.toLong() }.toLongArray()
        }
        noData = asciiTags[42113]?.trim()?.trimEnd('\u0000')?.toDoubleOrNull()
        val scale = tags[33550] ?: throw GeoFormatException("GeoTIFF sem ModelPixelScale")
        val tie = tags[33922] ?: throw GeoFormatException("GeoTIFF sem ModelTiepoint")
        sx = scale[0].toDouble(); sy = scale[1].toDouble()
        // GTRasterTypeGeoKey (1025): 1 = PixelIsArea, 2 = PixelIsPoint
        var pixelIsPoint = false
        tags[34735]?.let { k ->
            val n = k.getOrNull(3)?.toInt() ?: 0
            for (i in 0 until n) {
                val base = 4 + i * 4
                if (k.getOrNull(base)?.toInt() == 1025 && k.getOrNull(base + 3)?.toInt() == 2) pixelIsPoint = true
            }
        }
        val tieX = tie[3].toDouble() - tie[0].toDouble() * sx
        val tieY = tie[4].toDouble() + tie[1].toDouble() * sy
        // (x0, y0) = centro do pixel (0,0)
        x0 = if (pixelIsPoint) tieX else tieX + sx / 2
        y0 = if (pixelIsPoint) tieY else tieY - sy / 2
        west = x0 - sx / 2; north = y0 + sy / 2
        east = west + width * sx; south = north - height * sy
    }

    private fun readIfd(off: Long): Map<Int, List<Number>> {
        val tags = HashMap<Int, List<Number>>()
        val cnt = ByteArray(2)
        raf.seek(off); raf.readFully(cnt)
        val n = ByteBuffer.wrap(cnt).order(order).short.toInt() and 0xFFFF
        val entries = ByteArray(n * 12)
        raf.readFully(entries)
        val eb = ByteBuffer.wrap(entries).order(order)
        for (i in 0 until n) {
            val p = i * 12
            val tag = eb.getShort(p).toInt() and 0xFFFF
            val type = eb.getShort(p + 2).toInt() and 0xFFFF
            val count = eb.getInt(p + 4).toLong() and 0xFFFFFFFFL
            val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 1 }
            val total = (size * count).toInt()
            val data: ByteBuffer = if (total <= 4) {
                ByteBuffer.wrap(entries, p + 8, 4).slice().order(order)
            } else {
                val o = eb.getInt(p + 8).toLong() and 0xFFFFFFFFL
                val b = ByteArray(total)
                val save = raf.filePointer
                raf.seek(o); raf.readFully(b); raf.seek(save)
                ByteBuffer.wrap(b).order(order)
            }
            if (type == 2) {
                val b = ByteArray(total); data.get(b)
                asciiTags[tag] = String(b, Charsets.ISO_8859_1)
                continue
            }
            val vals = ArrayList<Number>(count.toInt().coerceAtMost(1_000_000))
            for (k in 0 until count.toInt()) {
                vals += when (type) {
                    1, 7 -> data.get().toInt() and 0xFF
                    6 -> data.get().toInt()
                    3 -> data.short.toInt() and 0xFFFF
                    8 -> data.short.toInt()
                    4 -> data.int.toLong() and 0xFFFFFFFFL
                    9 -> data.int
                    11 -> data.float.toDouble()
                    12 -> data.double
                    16 -> data.long
                    else -> 0
                }
            }
            tags[tag] = vals
        }
        return tags
    }

    @Synchronized
    private fun block(idx: Int): FloatArray = blockCache.getOrPut(idx) {
        val raw = ByteArray(counts[idx].toInt())
        raf.seek(offsets[idx]); raf.readFully(raw)
        val bps = bitsPerSample / 8
        val rowsInBlock = if (tiled) blockH else minOf(blockH, height - idx * blockH)
        val expected = blockW * rowsInBlock * bps
        val data = when (compression) {
            1 -> raw
            8, 32946 -> inflate(raw, expected)
            5 -> lzw(raw, expected)
            else -> throw GeoFormatException("Compressão TIFF $compression não suportada")
        }
        val bytes = if (data.size < expected) data.copyOf(expected) else data
        val stride = blockW * bps
        when (predictor) {
            2 -> for (r in 0 until rowsInBlock) {
                val bb = ByteBuffer.wrap(bytes, r * stride, stride).order(order)
                when (bps) {
                    2 -> { var prev = 0; for (c in 0 until blockW) { val i = r * stride + c * 2; val v = (bb.getShort(i).toInt() + prev).toShort(); bb.putShort(i, v); prev = v.toInt() } }
                    4 -> { var prev = 0; for (c in 0 until blockW) { val i = r * stride + c * 4; val v = bb.getInt(i) + prev; bb.putInt(i, v); prev = v } }
                    else -> for (c in 1 until stride) bytes[r * stride + c] = (bytes[r * stride + c] + bytes[r * stride + c - 1]).toByte()
                }
            }
            3 -> {
                val tmp = ByteArray(stride)
                for (r in 0 until rowsInBlock) {
                    val o = r * stride
                    for (c in 1 until stride) bytes[o + c] = (bytes[o + c] + bytes[o + c - 1]).toByte()
                    // bytes armazenados por plano (mais significativo primeiro); reordena para a ordem do arquivo
                    for (c in 0 until blockW) for (b in 0 until bps) {
                        val src = bytes[o + b * blockW + c]
                        val dst = if (order == ByteOrder.BIG_ENDIAN) c * bps + b else c * bps + (bps - 1 - b)
                        tmp[dst] = src
                    }
                    System.arraycopy(tmp, 0, bytes, o, stride)
                }
            }
        }
        val out = FloatArray(blockW * rowsInBlock)
        val bb = ByteBuffer.wrap(bytes).order(order)
        for (i in out.indices) {
            out[i] = when {
                sampleFormat == 3 && bps == 4 -> bb.getFloat(i * 4)
                sampleFormat == 3 && bps == 8 -> bb.getDouble(i * 8).toFloat()
                bps == 2 && sampleFormat == 1 -> (bb.getShort(i * 2).toInt() and 0xFFFF).toFloat()
                bps == 2 -> bb.getShort(i * 2).toFloat()
                bps == 4 -> bb.getInt(i * 4).toFloat()
                else -> (bytes[i].toInt() and 0xFF).toFloat()
            }
        }
        out
    }

    private fun inflate(raw: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(raw)
        val out = ByteArray(expected)
        var n = 0
        while (!inf.finished() && n < expected) {
            val r = inf.inflate(out, n, expected - n)
            if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
            n += r
        }
        inf.end()
        return out
    }

    private fun lzw(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        val dict = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
        var next = 258; var width = 9
        var bitPos = 0L
        var prev: ByteArray? = null
        fun read(): Int {
            var v = 0
            for (k in 0 until width) {
                val byteIdx = ((bitPos + k) shr 3).toInt()
                if (byteIdx >= input.size) return 257
                val bit = (input[byteIdx].toInt() shr (7 - ((bitPos + k) and 7).toInt())) and 1
                v = (v shl 1) or bit
            }
            bitPos += width
            return v
        }
        while (true) {
            val code = read()
            if (code == 257) break
            if (code == 256) {
                next = 258; width = 9; prev = null
                continue
            }
            val entry = when {
                code < next && dict[code] != null -> dict[code]!!
                prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (prev != null && next < 4096) dict[next++] = prev + entry[0]
            prev = entry
            if (next + 1 >= (1 shl width) && width < 12) width++
        }
        return out.toByteArray()
    }

    private fun pixel(col: Int, row: Int): Double? {
        if (col < 0 || row < 0 || col >= width || row >= height) return null
        val bx = col / blockW; val by = row / blockH
        val idx = if (tiled) by * ((width + blockW - 1) / blockW) + bx else by
        val b = block(idx)
        val v = b[(row - by * blockH) * blockW + (col - bx * blockW)].toDouble()
        if (v.isNaN() || (noData != null && v == noData) || v < -1000) return null
        return v
    }

    override fun sample(lat: Double, lon: Double): Double? {
        val x = (lon - x0) / sx
        val y = (y0 - lat) / sy
        return bilinear(x, y) { c, r -> pixel(c.coerceIn(0, width - 1), r.coerceIn(0, height - 1)) }
    }

    override fun close() = raf.close()
}
