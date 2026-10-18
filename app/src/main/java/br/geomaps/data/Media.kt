package br.geomaps.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import br.geomaps.core.Coord
import br.geomaps.core.FeatureStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/** Ícones e fotos armazenados nos arquivos internos do app. */
class MediaStore(private val context: Context) {
    val iconDir = File(context.filesDir, "icons").apply { mkdirs() }
    val photoDir = File(context.filesDir, "photos").apply { mkdirs() }

    private fun hash(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    /** Guarda uma imagem de ícone e devolve a chave de estilo "img:<arquivo>". */
    fun saveIcon(bytes: ByteArray): String? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val name = hash(bytes) + ".png"
        val f = File(iconDir, name)
        if (!f.exists()) {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return "img:$name"
    }

    /** Baixa um ícone http(s) (ex.: ícones padrão do Google Earth) e guarda localmente. */
    fun downloadIcon(url: String): String? = runCatching {
        val cached = File(iconDir, "url_" + hash(url.toByteArray()) + ".png")
        if (cached.exists()) return "img:${cached.name}"
        val c = URL(url.replace("http://maps.google.com", "https://maps.google.com")).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", Net.USER_AGENT)
        val bytes = c.inputStream.use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        "img:${cached.name}"
    }.getOrNull()

    fun importedIcons(): List<String> = iconDir.listFiles()?.filter { it.name.endsWith(".png") }?.sortedBy { it.lastModified() }?.map { "img:${it.name}" } ?: emptyList()

    fun savePhoto(bytes: ByteArray, ext: String = "jpg"): String {
        val name = "foto_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + "_" + hash(bytes).take(6) + "." + ext
        File(photoDir, name).writeBytes(bytes)
        return name
    }

    fun photoFile(name: String) = File(photoDir, name)
    fun photoBytes(name: String): ByteArray? = photoFile(name).takeIf { it.exists() }?.readBytes()

    /** Copia uma foto (galeria/câmera) e lê posição/altitude/data do EXIF. */
    fun importPhoto(uri: Uri): Pair<String, Coord?>? {
        val cr = context.contentResolver
        val src = if (android.os.Build.VERSION.SDK_INT >= 29 && uri.authority == "media") {
            runCatching { android.provider.MediaStore.setRequireOriginal(uri) }.getOrNull() ?: uri
        } else uri
        val bytes = runCatching { cr.openInputStream(src)?.use { it.readBytes() } }.getOrNull()
            ?: cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = savePhoto(bytes, "jpg")
        return name to exifCoord(photoFile(name))
    }

    fun exifCoord(f: File): Coord? = runCatching {
        val ex = ExifInterface(f.absolutePath)
        val ll = ex.latLong ?: return null
        val alt = ex.getAltitude(Double.NaN).takeIf { !it.isNaN() }
        val time = ex.dateTimeOriginal ?: ex.dateTime
        Coord(ll[0], ll[1], alt, time)
    }.getOrNull()

    /** Grava a posição no EXIF de uma foto tirada pelo app. */
    fun writeExif(f: File, c: Coord) {
        runCatching {
            val ex = ExifInterface(f.absolutePath)
            ex.setLatLong(c.lat, c.lon)
            c.ele?.let { ex.setAltitude(it) }
            ex.saveAttributes()
        }
    }
}

object Net {
    const val USER_AGENT = "GeoMaps/0.1 (Android; uso pessoal)"

    fun open(url: String, timeout: Int = 20000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.instanceFollowRedirects = true
        return c
    }
}

/** Desenha os ícones dos pontos (marcador colorido com emoji ou imagem importada). */
class IconFactory(private val context: Context, private val media: MediaStore) {
    private val density = context.resources.displayMetrics.density
    private val cache = HashMap<String, Bitmap>()

    companion object {
        val EMOJIS = listOf(
            "📍", "📌", "⭐", "🎯", "🚩", "🏁", "⚠️", "❌", "✅", "❓", "ℹ️",
            "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚫", "⚪",
            "🏠", "🏢", "🏭", "🏫", "🏥", "⛪", "🏪", "🏛️",
            "💧", "🚰", "🔥", "⚡", "💡", "🔧", "🛠️", "📡", "🗼", "🚧", "🗑️",
            "🌳", "🌲", "🌵", "🌾", "🍃", "⛰️", "🏔️", "🌊", "🏞️", "⛺",
            "🚗", "🚜", "🚚", "🅿️", "⛽", "🛤️", "🌉", "🐄", "🐎", "📷",
        )
    }

    fun imageId(iconKey: String, color: Int, scale: Float): String = "ic_" + Integer.toHexString((iconKey + "|" + color + "|" + scale).hashCode())

    fun bitmap(iconKey: String, color: Int, scale: Float = 1f): Bitmap {
        val id = imageId(iconKey, color, scale)
        return cache.getOrPut(id) { render(iconKey, color, scale) }
    }

    private fun render(iconKey: String, color: Int, scale: Float): Bitmap {
        val s = scale.coerceIn(0.4f, 3f)
        if (iconKey.startsWith("img:")) {
            val f = File(media.iconDir, iconKey.removePrefix("img:"))
            val src = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            if (src != null) {
                val target = (30 * density * s).toInt().coerceAtLeast(8)
                val ratio = target.toFloat() / maxOf(src.width, src.height)
                val w = (src.width * ratio).toInt().coerceAtLeast(1); val h = (src.height * ratio).toInt().coerceAtLeast(1)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(out)
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                if (color != Color.WHITE && Color.alpha(color) != 0) p.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
                cv.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
                return out
            }
        }
        val emoji = if (iconKey.startsWith("e:")) iconKey.removePrefix("e:") else ""
        return pin(if (Color.alpha(color) == 0) FeatureStyle.DEFAULT_LINE else color, emoji, s)
    }

    /** Marcador em forma de gota com o emoji no centro. A ponta fica na base da imagem. */
    private fun pin(color: Int, emoji: String, s: Float): Bitmap {
        val w = (30 * density * s).toInt()
        val h = (40 * density * s).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = w / 2f - density
        val cx = w / 2f; val cy = r + density
        val path = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.62f, cy + r * 0.78f)
            lineTo(cx, h.toFloat() - density)
            lineTo(cx + r * 0.62f, cy + r * 0.78f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color or 0xFF000000.toInt(); style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.6f * density }
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
        c.drawCircle(cx, cy, r * 0.72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        if (emoji.isNotEmpty() && emoji != "📍") {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = r * 1.0f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
            val fm = tp.fontMetrics
            c.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2, tp)
        } else {
            c.drawCircle(cx, cy, r * 0.32f, fill)
        }
        return bmp
    }

    fun png(iconKey: String, color: Int): ByteArray {
        val b = bitmap(iconKey, color, 1.6f)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Ícone de posição atual (seta de rumo). */
    fun locationArrow(): Bitmap {
        val sz = (34 * density).toInt()
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Path().apply {
            moveTo(sz / 2f, 2 * density); lineTo(sz - 7 * density, sz - 6 * density)
            lineTo(sz / 2f, sz - 12 * density); lineTo(7 * density, sz - 6 * density); close()
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt() })
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density })
        return bmp
    }
}
