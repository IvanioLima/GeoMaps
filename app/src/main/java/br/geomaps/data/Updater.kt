package br.geomaps.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import br.geomaps.BuildConfig
import br.geomaps.core.Json
import br.geomaps.core.jNum
import br.geomaps.core.jObj
import br.geomaps.core.jStr
import java.io.File
import java.security.MessageDigest

/** Informações da versão publicada (latest.json). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String?,
    val size: Long,
)

/**
 * Atualização do app sem login: o GitHub Actions publica o APK e um latest.json como release do
 * repositório público IvanioLima/GeoMaps-releases (só binários, sem código); o app compara o
 * versionCode, baixa o APK, confere o SHA-256 e abre o instalador do Android.
 */
class Updater(private val context: Context, private val settings: SettingsStore) {
    companion object {
        const val REPO = "https://github.com/IvanioLima/GeoMaps-releases/releases/"
        const val LATEST = REPO + "latest/download/latest.json"
        private const val DAY = 24 * 3600 * 1000L
    }

    val currentCode get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    fun online(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Verificação automática: no máximo uma vez por dia. */
    fun shouldAutoCheck(): Boolean = System.currentTimeMillis() - settings.getLong("upd_last_check") > DAY

    /** Consulta a versão publicada. Retorna null se não houver versão mais nova. */
    fun check(ignoreSkipped: Boolean): UpdateInfo? {
        settings.putLong("upd_last_check", System.currentTimeMillis())
        val c = Net.open(LATEST, 15000)
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode == 404) return null // ainda não há versão publicada
        if (c.responseCode !in 200..299) throw IllegalStateException("Servidor de atualização respondeu HTTP ${c.responseCode}")
        val m = Json.parse(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jObj() ?: return null
        val info = UpdateInfo(
            versionCode = m["versionCode"].jNum()?.toInt() ?: return null,
            versionName = m["versionName"].jStr() ?: "",
            notes = m["notes"].jStr() ?: "",
            apkUrl = m["apk"].jStr()?.let { if (it.startsWith("http")) it else REPO + "latest/download/" + it } ?: return null,
            sha256 = m["sha256"].jStr(),
            size = m["size"].jNum()?.toLong() ?: 0,
        )
        if (info.versionCode <= currentCode) return null
        if (!ignoreSkipped && settings.getLong("upd_skip") == info.versionCode.toLong()) return null
        return info
    }

    fun skip(info: UpdateInfo) = settings.putLong("upd_skip", info.versionCode.toLong())

    /** Baixa o APK (com progresso 0..1) e confere a integridade. */
    fun download(info: UpdateInfo, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "GeoMaps-${info.versionName.ifBlank { info.versionCode.toString() }}.apk")
        val c = Net.open(info.apkUrl, 60000)
        if (c.responseCode !in 200..299) throw IllegalStateException("Falha no download (HTTP ${c.responseCode})")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (total > 0 && done - last > 256 * 1024) { last = done; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
        }
        progress(1f)
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (!info.sha256.isNullOrBlank() && !hash.equals(info.sha256, true)) {
            out.delete()
            throw IllegalStateException("Arquivo baixado corrompido (SHA-256 não confere). Tente de novo.")
        }
        return out
    }

    /** O Android exige liberar "instalar apps desconhecidos" para o GeoMaps uma única vez. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
