package br.geomaps.data

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.geomaps.GeoMapsApp
import br.geomaps.core.Codec
import br.geomaps.core.GeoFeature
import br.geomaps.core.Json
import br.geomaps.core.Project
import br.geomaps.core.jArr
import br.geomaps.core.jObj
import br.geomaps.core.jStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sincronização dos "Meus Mapas" com a pasta oculta do app no Google Drive (appDataFolder).
 *
 * Cada projeto é um arquivo (p_<uid>.json.gz) com seus elementos; fotos e ícones importados são
 * arquivos à parte (m_photo_<nome>, m_icon_<nome>), enviados uma única vez.
 * Regras:
 *  - projeto novo/alterado aqui → envia; alterado no Drive → baixa e substitui;
 *  - alterado nos dois lados desde a última sincronização → conflito: a versão do Drive fica no
 *    projeto e a versão deste aparelho vira um projeto novo "(conflito …)", que também é enviado;
 *  - excluído aqui → apaga no Drive (se ninguém alterou lá nesse meio tempo);
 *  - excluído no Drive → apaga aqui (se não houver alterações locais pendentes; se houver, reenvia).
 */
class SyncManager(private val app: GeoMapsApp) {
    data class State(
        val running: Boolean = false,
        val lastSync: Long = 0,
        val error: String? = null,
        val needsSignIn: Boolean = false,
        val message: String = "",
    )

    private val db get() = app.db
    private val settings get() = app.settings
    private val _state = MutableStateFlow(State(lastSync = app.settings.getLong("sync_last")))
    val state: StateFlow<State> = _state
    /** Incrementa quando dados vindos do Drive alteram o banco local (a tela de edição zera o desfazer). */
    val remoteApplied = MutableStateFlow(0)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounce: Job? = null

    val enabled get() = settings.value.syncAccount.isNotBlank()

    companion object {
        private const val WORK = "geomaps-sync"
        private const val GZ = "application/gzip"
    }

    // ------------------------------------------------------------------ gatilhos

    /** Sincroniza assim que houver internet (ao abrir o app, manual). */
    fun requestSync() {
        if (!enabled) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** Chamado a cada alteração local (importar, criar, editar, excluir): envia alguns segundos depois. */
    fun onLocalChange() {
        if (!enabled) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(4000)
            if (db.hasPendingSync()) requestSync()
        }
    }

    // ------------------------------------------------------------------ conta

    /** Conta conectada: se for outra conta, tudo é reenviado para o Drive dela. */
    fun connected(email: String) {
        val last = settings.getString("sync_last_account")
        if (last != null && !last.equals(email, true)) db.resetSync()
        settings.putString("sync_last_account", email)
        settings.update { it.copy(syncAccount = email) }
        _state.value = _state.value.copy(needsSignIn = false, error = null)
        requestSync()
    }

    fun disconnect() {
        settings.update { it.copy(syncAccount = "") }
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
        _state.value = State(lastSync = _state.value.lastSync)
    }

    // ------------------------------------------------------------------ execução

    /** Executa a sincronização completa. Retorna false se deve tentar de novo mais tarde. */
    suspend fun run(): Boolean {
        mutex.withLock { return kotlinx.coroutines.withContext(Dispatchers.IO) { runLocked() } }
    }

    private fun runLocked(): Boolean {
        val account = settings.value.syncAccount
        if (account.isBlank()) return true
        _state.value = _state.value.copy(running = true, error = null, message = "Sincronizando…")
        try {
            var token: String = DriveAuth.tokenSilently(app, account) ?: run {
                _state.value = _state.value.copy(running = false, needsSignIn = true, error = "Conecte a conta Google novamente em Configurações")
                return true
            }
            var changed = false
            // passa de novo se algo foi editado durante o envio (no máximo 3 vezes)
            for (pass in 0 until 3) {
                val r = try {
                    syncOnce(DriveApi(token))
                } catch (e: DriveAuthException) {
                    DriveAuth.invalidate(app, token)
                    token = DriveAuth.tokenSilently(app, account) ?: throw e
                    syncOnce(DriveApi(token))
                }
                changed = changed || r
                if (!db.hasPendingSync()) break
            }
            val now = System.currentTimeMillis()
            settings.putLong("sync_last", now)
            _state.value = State(lastSync = now, message = "Sincronizado")
            if (changed) {
                app.repo.notifyChanged()
                remoteApplied.value = remoteApplied.value + 1
            }
            return true
        } catch (e: Exception) {
            _state.value = _state.value.copy(running = false, error = "Falha na sincronização: ${e.message ?: e.javaClass.simpleName}")
            // erro de rede: o WorkManager tenta de novo mais tarde
            return !(e is java.io.IOException && e !is DriveAuthException)
        }
    }

    /** Uma passada completa. Retorna true se o banco local recebeu dados do Drive. */
    private fun syncOnce(api: DriveApi): Boolean {
        val all = api.list()
        val remote = all.filter { it.props["type"] == "project" && !it.props["uid"].isNullOrBlank() }.associateBy { it.props["uid"]!! }
        val media = all.filter { it.props["type"] == "media" }.associateBy { it.name }.toMutableMap()
        var changed = false
        val gone = HashSet<String>()

        // 1) exclusões feitas neste aparelho
        for (t in db.tombstones()) {
            val r = remote[t.uid]
            if (r != null && r.id == t.driveId && (t.rev == null || r.version == t.rev)) {
                api.delete(r.id); gone += t.uid
            }
            // se foi alterado em outro aparelho depois da exclusão, a versão do Drive volta (passo 3)
            db.deleteTombstone(t.uid)
        }

        // 2) projetos deste aparelho
        val counts = db.projects().associate { it.id to it.featureCount }
        val locals = db.syncInfos()
        for (p in locals) {
            val r = remote[p.uid]
            when {
                // o projeto vazio padrão de um aparelho novo não é enviado quando o Drive já tem projetos
                r == null && p.driveId == null && remote.isNotEmpty() && counts[p.id] == 0 && p.name == "Meu mapa" -> {
                    db.deleteProjectFromRemote(p.id); changed = true
                }
                r == null && p.driveId == null -> upload(api, p, null, media)
                r == null -> {
                    // estava no Drive e foi excluído em outro aparelho
                    if (p.dirty) upload(api, p, null, media)
                    else { db.deleteProjectFromRemote(p.id); changed = true }
                }
                p.rev != null && r.version == p.rev -> if (p.dirty) upload(api, p, r.id, media)
                !p.dirty -> { download(api, r, p.id, media); changed = true }
                else -> { conflict(api, p, r, media); changed = true }
            }
        }

        // 3) projetos novos no Drive (criados em outro aparelho)
        val localUids = db.syncInfos().map { it.uid }.toSet() + locals.map { it.uid }
        for ((uid, r) in remote) {
            if (uid in localUids || uid in gone) continue
            download(api, r, null, media); changed = true
        }
        return changed
    }

    // ------------------------------------------------------------------ envio / recebimento

    private fun mediaKeys(fs: List<GeoFeature>): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (f in fs) {
            for (ph in f.photos) out += "m_photo_$ph" to app.media.photoFile(ph)
            val icon = f.style.icon
            if (icon.startsWith("img:")) out += "m_icon_${icon.removePrefix("img:")}" to File(app.media.iconDir, icon.removePrefix("img:"))
        }
        return out.distinctBy { it.first }
    }

    private fun upload(api: DriveApi, p: Db.SyncInfo, fileId: String?, media: MutableMap<String, DriveApi.RFile>) {
        val project = db.projects().firstOrNull { it.id == p.id } ?: return
        val fs = db.features(listOf(p.id))
        for ((name, file) in mediaKeys(fs)) {
            if (name in media || !file.exists()) continue
            media[name] = api.create(name, mapOf("type" to "media"), file.readBytes(), "application/octet-stream")
        }
        val payload = Json.write(linkedMapOf(
            "format" to "geomaps-project", "v" to 1, "uid" to p.uid,
            "name" to project.name, "description" to project.description, "form" to Codec.formToJson(project.form),
            "created" to project.created, "updated" to project.updated,
            "device" to Build.MODEL, "saved" to System.currentTimeMillis(),
            "features" to fs.map { Codec.featureToMap(it) },
        ))
        val bytes = gzip(payload.toByteArray())
        val props = mapOf("type" to "project", "uid" to p.uid, "name" to project.name.take(100))
        val rf = if (fileId == null) api.create("p_${p.uid}.json.gz", props, bytes, GZ) else api.update(fileId, props, bytes, GZ)
        db.markSynced(p.id, rf.id, rf.version, p.seq)
    }

    private fun download(api: DriveApi, r: DriveApi.RFile, localId: Long?, media: Map<String, DriveApi.RFile>) {
        val m = Json.parse(gunzip(api.download(r.id)).toString(Charsets.UTF_8)).jObj() ?: throw java.io.IOException("Arquivo de projeto inválido no Drive")
        val uid = m["uid"].jStr() ?: r.props["uid"]!!
        val project = Project(
            name = m["name"].jStr() ?: "Projeto", description = m["description"].jStr() ?: "",
            form = Codec.formFromJson(m["form"].jStr()),
            created = (m["created"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updated = (m["updated"] as? Number)?.toLong() ?: System.currentTimeMillis(),
        )
        val fs = m["features"].jArr()?.mapNotNull { f -> f.jObj()?.let { runCatching { Codec.featureFromMap(it) }.getOrNull() } } ?: emptyList()
        // fotos e ícones que ainda não existem neste aparelho
        for ((name, file) in mediaKeys(fs)) {
            if (file.exists()) continue
            val rf = media[name] ?: continue
            runCatching { file.parentFile?.mkdirs(); file.writeBytes(api.download(rf.id)) }
        }
        if (localId != null && fs.isNotEmpty()) runCatching { app.repo.snapshot(localId, "Antes de receber do Google Drive") }
        db.applyRemoteProject(localId, uid, project, fs, r.id, r.version)
    }

    /** Editado aqui e no Drive: a versão do Drive fica no projeto; a daqui vira um projeto novo. */
    private fun conflict(api: DriveApi, p: Db.SyncInfo, r: DriveApi.RFile, media: MutableMap<String, DriveApi.RFile>) {
        val project = db.projects().firstOrNull { it.id == p.id } ?: return
        val fs = db.features(listOf(p.id))
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(System.currentTimeMillis())
        val copyId = db.insertProject(project.copy(id = 0, name = "${project.name} (conflito ${Build.MODEL} $stamp)"))
        db.insertFeatures(fs.map { it.copy(id = 0, projectId = copyId) })
        download(api, r, p.id, media)
        db.syncInfos().firstOrNull { it.id == copyId }?.let { upload(api, it, null, media) }
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}

/** Trabalho do WorkManager: roda a sincronização quando houver internet (mesmo com o app fechado). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GeoMapsApp
        return if (app.sync.run()) Result.success() else Result.retry()
    }
}
