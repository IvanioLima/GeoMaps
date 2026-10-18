package br.geomaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gera localmente as fontes SDF (formato PBF do MapLibre) usadas nos rótulos dos pontos,
 * para que os nomes apareçam mesmo sem internet. Algoritmo equivalente ao TinySDF.
 */
object Glyphs {
    const val FONT = "GeoMapsSans"
    private const val SIZE = 24f
    private const val BUFFER = 3
    private const val RADIUS = 8.0
    private const val CUTOFF = 0.25
    private const val VERSION = "2"
    private val RANGES = listOf(0, 256, 8192)

    fun dir(ctx: Context) = File(ctx.filesDir, "glyphs")

    fun urlTemplate(ctx: Context) = "file://" + dir(ctx).absolutePath + "/{fontstack}/{range}.pbf"

    /** Cria os arquivos na primeira execução (≈1 s). */
    fun ensure(ctx: Context) {
        val d = File(dir(ctx), FONT)
        val marker = File(d, "v$VERSION")
        if (marker.exists()) return
        d.mkdirs()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = SIZE
            color = Color.WHITE
        }
        for (start in RANGES) {
            val bytes = range(start, start + 255, paint)
            File(d, "$start-${start + 255}.pbf").writeBytes(bytes)
        }
        marker.writeText("ok")
    }

    private fun range(start: Int, end: Int, paint: Paint): ByteArray {
        val stack = ByteArrayOutputStream()
        field(stack, 1, FONT.toByteArray())
        field(stack, 2, "$start-$end".toByteArray())
        for (code in start..end) {
            val g = glyph(code, paint) ?: continue
            field(stack, 3, g)
        }
        val out = ByteArrayOutputStream()
        field(out, 1, stack.toByteArray())
        return out.toByteArray()
    }

    private fun glyph(code: Int, paint: Paint): ByteArray? {
        if (code < 32 || code in 127..159) return null
        val s = String(Character.toChars(code))
        if (!paint.hasGlyph(s)) return null
        val advance = paint.measureText(s).roundToInt()
        val r = Rect()
        paint.getTextBounds(s, 0, s.length, r)
        val out = ByteArrayOutputStream()
        varField(out, 1, code.toLong())
        if (r.width() <= 0 || r.height() <= 0) {
            varField(out, 3, 0); varField(out, 4, 0)
            varField(out, 5, zz(0)); varField(out, 6, zz(-23))
            varField(out, 7, advance.toLong())
            return out.toByteArray()
        }
        val gw = r.width(); val gh = r.height()
        val w = gw + 2 * BUFFER; val h = gh + 2 * BUFFER
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, (BUFFER - r.left).toFloat(), (BUFFER - r.top).toFloat(), paint)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val inf = 1e20
        val outer = DoubleArray(w * h) { inf }
        val inner = DoubleArray(w * h)
        for (i in px.indices) {
            val a = ((px[i] ushr 24) and 0xFF) / 255.0
            if (a == 0.0) continue
            if (a == 1.0) { outer[i] = 0.0; inner[i] = inf } else {
                val d = 0.5 - a
                outer[i] = if (d > 0) d * d else 0.0
                inner[i] = if (d < 0) d * d else 0.0
            }
        }
        val n = max(w, h)
        val f = DoubleArray(n); val z = DoubleArray(n + 1); val v = IntArray(n)
        edt(outer, w, h, f, v, z)
        edt(inner, w, h, f, v, z)
        val data = ByteArray(w * h)
        for (i in data.indices) {
            val d = sqrt(outer[i]) - sqrt(inner[i])
            data[i] = min(255.0, max(0.0, Math.round(255 - 255 * (d / RADIUS + CUTOFF)).toDouble())).toInt().toByte()
        }
        field(out, 2, data)
        varField(out, 3, gw.toLong()); varField(out, 4, gh.toLong())
        varField(out, 5, zz(r.left)); varField(out, 6, zz(-r.top - 23))
        varField(out, 7, advance.toLong())
        return out.toByteArray()
    }

    private fun edt(grid: DoubleArray, w: Int, h: Int, f: DoubleArray, v: IntArray, z: DoubleArray) {
        for (x in 0 until w) edt1d(grid, x, w, h, f, v, z)
        for (y in 0 until h) edt1d(grid, y * w, 1, w, f, v, z)
    }

    private fun edt1d(grid: DoubleArray, offset: Int, stride: Int, length: Int, f: DoubleArray, v: IntArray, z: DoubleArray) {
        v[0] = 0; z[0] = -1e20; z[1] = 1e20
        f[0] = grid[offset]
        var k = 0
        for (q in 1 until length) {
            f[q] = grid[offset + q * stride]
            val q2 = (q * q).toDouble()
            var s: Double
            while (true) {
                val r = v[k]
                s = (f[q] - f[r] + q2 - r.toDouble() * r) / (q - r) / 2
                if (s <= z[k]) {
                    k--
                    if (k < 0) break
                } else break
            }
            k++
            v[k] = q; z[k] = s; z[k + 1] = 1e20
        }
        k = 0
        for (q in 0 until length) {
            while (z[k + 1] < q) k++
            val r = v[k]
            val qr = (q - r).toDouble()
            grid[offset + q * stride] = f[r] + qr * qr
        }
    }

    private fun zz(n: Int): Long = ((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    private fun varField(out: ByteArrayOutputStream, num: Int, value: Long) {
        varint(out, (num shl 3).toLong())
        varint(out, value)
    }

    private fun field(out: ByteArrayOutputStream, num: Int, bytes: ByteArray) {
        varint(out, ((num shl 3) or 2).toLong())
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }
}
