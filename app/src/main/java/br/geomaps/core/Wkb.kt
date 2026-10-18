package br.geomaps.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WKB (ISO/OGC/EWKB) e cabeçalho de geometria GeoPackage. */
object Wkb {

    fun write(g: Geom): ByteArray {
        val z = g.allCoords().any { it.ele != null }
        val out = ByteArrayOutputStream()
        fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun head(type: Int) { out.write(buf(5).put(1).putInt(if (z) type + 1000 else type).array()) }
        fun pt(c: Coord) {
            val b = buf(if (z) 24 else 16).putDouble(c.lon).putDouble(c.lat)
            if (z) b.putDouble(c.ele ?: 0.0)
            out.write(b.array())
        }
        fun count(n: Int) = out.write(buf(4).putInt(n).array())
        fun ring(r: List<Coord>) { val cl = if (r.isNotEmpty()) r + r.first() else r; count(cl.size); cl.forEach { pt(it) } }
        when (g) {
            is Geom.Pt -> { head(1); pt(g.c) }
            is Geom.Line -> if (g.parts.size == 1) { head(2); count(g.parts[0].size); g.parts[0].forEach { pt(it) } }
            else { head(5); count(g.parts.size); g.parts.forEach { p -> head(2); count(p.size); p.forEach { pt(it) } } }
            is Geom.Poly -> if (g.polys.size == 1) { head(3); count(g.polys[0].size); g.polys[0].forEach { ring(it) } }
            else { head(6); count(g.polys.size); g.polys.forEach { p -> head(3); count(p.size); p.forEach { ring(it) } } }
        }
        return out.toByteArray()
    }

    /** Blob GeoPackage: "GP", versão 0, flags (envelope xy, little-endian), srs_id, envelope, WKB. */
    fun gpkgBlob(g: Geom, srsId: Int = 4326): ByteArray {
        val wkb = write(g)
        val b = GeoMath.bounds(g.allCoords())!!
        val bb = ByteBuffer.allocate(8 + 32 + wkb.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(0x03) // envelope tipo 1 (bit 1) + little endian (bit 0)
        bb.putInt(srsId)
        bb.putDouble(b.minLon).putDouble(b.maxLon).putDouble(b.minLat).putDouble(b.maxLat)
        bb.put(wkb)
        return bb.array()
    }

    fun fromGpkgBlob(blob: ByteArray): List<Geom> {
        if (blob.size < 8 || blob[0] != 'G'.code.toByte() || blob[1] != 'P'.code.toByte()) return read(blob)
        val flags = blob[3].toInt()
        val envType = (flags shr 1) and 0x07
        val envLen = when (envType) { 0 -> 0; 1 -> 32; 2, 3 -> 48; 4 -> 64; else -> 0 }
        return read(blob.copyOfRange(8 + envLen, blob.size))
    }

    /** Lê WKB; geometrias multi-tipo heterogêneas são devolvidas separadas. */
    fun read(b: ByteArray): List<Geom> {
        val r = Reader(ByteBuffer.wrap(b))
        return r.geom()
    }

    private class Reader(val bb: ByteBuffer) {
        fun geom(): List<Geom> {
            val order = bb.get().toInt()
            bb.order(if (order == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            var t = bb.int.toLong() and 0xFFFFFFFFL
            var hasZ = false; var hasM = false
            // EWKB
            if (t and 0x80000000L != 0L) hasZ = true
            if (t and 0x40000000L != 0L) hasM = true
            if (t and 0x20000000L != 0L) bb.int // SRID
            t = t and 0x0FFFFFFFL
            when {
                t >= 3000 -> { hasZ = true; hasM = true; t -= 3000 }
                t >= 2000 -> { hasM = true; t -= 2000 }
                t >= 1000 -> { hasZ = true; t -= 1000 }
            }
            fun c(): Coord {
                val x = bb.double; val y = bb.double
                val z = if (hasZ) bb.double else null
                if (hasM) bb.double
                return Coord(y, x, z?.takeIf { !it.isNaN() })
            }
            fun pts(): List<Coord> = (0 until bb.int).map { c() }
            return when (t.toInt()) {
                1 -> listOf(Geom.Pt(c()))
                2 -> listOf(Geom.Line.of(pts()))
                3 -> listOf(Geom.Poly(listOf((0 until bb.int).map { Geom.Poly.openRing(pts()) })))
                4, 5, 6, 7 -> {
                    val n = bb.int
                    val subs = (0 until n).flatMap { geom() }
                    val lines = subs.filterIsInstance<Geom.Line>()
                    val polys = subs.filterIsInstance<Geom.Poly>()
                    val out = ArrayList<Geom>()
                    out += subs.filterIsInstance<Geom.Pt>()
                    if (lines.isNotEmpty()) out += Geom.Line(lines.flatMap { it.parts })
                    if (polys.isNotEmpty()) out += Geom.Poly(polys.flatMap { it.polys })
                    out
                }
                else -> throw GeoFormatException("WKB tipo $t não suportado")
            }
        }
    }
}
