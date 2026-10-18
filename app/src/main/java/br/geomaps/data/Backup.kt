package br.geomaps.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.geomaps.GeoMapsApp
import br.geomaps.core.Codec
import br.geomaps.core.GeoFormatException
import br.geomaps.core.Json
import br.geomaps.core.Project
import br.geomaps.core.jArr
import br.geomaps.core.jObj
import br.geomaps.core.jStr
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup completo (.geomaps = zip com JSON + ícones + fotos). O arquivo pode ser salvo
 * no Google Drive pelo seletor de arquivos do Android.
 */
object Backup {

    fun write(repo: Repo, out: OutputStream, projectIds: List<Long>? = null) {
        val projects = repo.projects().filter { projectIds == null || it.id in projectIds }
        val usedIcons = HashSet<String>()
        val usedPhotos = HashSet<String>()
        val data = projects.map { p ->
            val fs = repo.features(listOf(p.id))
            fs.forEach { f ->
                if (f.style.icon.startsWith("img:")) usedIcons += f.style.icon.removePrefix("img:")
                usedPhotos += f.photos
            }
            mapOf(
                "name" to p.name, "description" to p.description, "visible" to p.visible,
                "form" to Codec.formToJson(p.form), "features" to fs.map { Codec.featureToMap(it) },
            )
        }
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("geomaps.json"))
            z.write(Json.write(mapOf("app" to "GeoMaps", "version" to 1, "created" to System.currentTimeMillis(), "projects" to data)).toByteArray())
            z.closeEntry()
            for (i in usedIcons) {
                val f = File(repo.media.iconDir, i)
                if (f.exists()) { z.putNextEntry(ZipEntry("icons/$i")); z.write(f.readBytes()); z.closeEntry() }
            }
            for (p in usedPhotos) {
                val f = repo.media.photoFile(p)
                if (f.exists()) { z.putNextEntry(ZipEntry("photos/$p")); z.write(f.readBytes()); z.closeEntry() }
            }
        }
    }

    /** Restaura um backup como projetos novos. Retorna a quantidade de projetos restaurados. */
    fun restore(repo: Repo, bytes: ByteArray): Int {
        var json: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val b = z.readBytes()
                when {
                    e.name == "geomaps.json" -> json = String(b)
                    e.name.startsWith("icons/") -> File(repo.media.iconDir, e.name.removePrefix("icons/").substringAfterLast('/')).writeBytes(b)
                    e.name.startsWith("photos/") -> repo.media.photoFile(e.name.removePrefix("photos/").substringAfterLast('/')).writeBytes(b)
                }
            }
        }
        val root = Json.parse(json ?: throw GeoFormatException("Arquivo de backup inválido")).jObj() ?: throw GeoFormatException("Backup inválido")
        val existing = repo.projects().map { it.name }.toSet()
        var n = 0
        for (po in root["projects"].jArr() ?: emptyList()) {
            val m = po.jObj() ?: continue
            var name = m["name"].jStr() ?: "Projeto"
            if (name in existing) name += " (restaurado)"
            val pid = repo.db.insertProject(Project(name = name, description = m["description"].jStr() ?: "", visible = m["visible"] != false, form = Codec.formFromJson(m["form"].jStr())))
            val fs = m["features"].jArr()?.mapNotNull { it.jObj()?.let { fm -> runCatching { Codec.featureFromMap(fm, pid) }.getOrNull() } } ?: emptyList()
            repo.db.insertFeatures(fs)
            n++
        }
        repo.notifyChanged()
        return n
    }

    fun autoDir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

    fun runAuto(context: Context, repo: Repo) {
        val dir = autoDir(context)
        val f = File(dir, "geomaps_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(System.currentTimeMillis()) + ".geomaps")
        f.outputStream().use { write(repo, it) }
        dir.listFiles()?.filter { it.name.endsWith(".geomaps") }?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
    }

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork("auto_backup"); return }
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork("auto_backup", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val app = applicationContext as GeoMapsApp
            Backup.runAuto(applicationContext, app.repo)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
