package br.geomaps.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.geomaps.GeoMapsApp
import br.geomaps.core.Coord
import br.geomaps.core.CoordFormat
import br.geomaps.core.ExportFormat
import br.geomaps.core.FeatureStyle
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeoMath
import br.geomaps.core.Geom
import br.geomaps.core.GeomType
import br.geomaps.core.ImportResult
import br.geomaps.core.Profile
import br.geomaps.core.Project
import br.geomaps.core.TrackOps
import br.geomaps.data.Backup
import br.geomaps.data.Db
import br.geomaps.data.Settings
import br.geomaps.location.Fix
import br.geomaps.location.TrackRecordingService
import br.geomaps.map.BaseMap
import br.geomaps.map.BaseMaps
import br.geomaps.map.MapController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as GeoMapsApp
    val repo = app.repo
    val settings: StateFlow<Settings> = app.settings.flow
    val map = MapController(application, app.icons)
    val hub = app.locationHub
    val recorder = app.recorder
    val offline = app.offline

    val projects = MutableStateFlow<List<Project>>(emptyList())
    val features = MutableStateFlow<List<GeoFeature>>(emptyList())
    val selectedId = MutableStateFlow<Long?>(null)
    val mode = MutableStateFlow<Mode>(Mode.Browse)
    val screen = MutableStateFlow(Screen.NONE)
    val screenArg = MutableStateFlow(0L)
    val dialog = MutableStateFlow<Dialog?>(null)
    val follow = MutableStateFlow(FollowMode.OFF)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow<String?>(null)
    val profile = MutableStateFlow<ProfileState?>(null)
    val nav = MutableStateFlow<NavState?>(null)
    val customSources = MutableStateFlow<List<Db.CustomSource>>(emptyList())
    val exportRequest = MutableStateFlow<ExportRequest?>(null)
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)
    val heading = MutableStateFlow<Float?>(null)
    val freehandColor = MutableStateFlow(0xFFE53935.toInt())

    val fix: StateFlow<Fix?> = hub.fix
    val selected: StateFlow<GeoFeature?> = combine(features, selectedId) { fs, id -> fs.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val activeProject: StateFlow<Project?> = combine(projects, settings) { ps, s -> ps.firstOrNull { it.id == s.activeProjectId } ?: ps.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Edit(val label: String, val before: List<GeoFeature>, val after: List<GeoFeature>)
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var lastAlert = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pid = repo.ensureDefaultProject()
            if (settings.value.activeProjectId == 0L || repo.projects().none { it.id == settings.value.activeProjectId }) {
                app.settings.update { it.copy(activeProjectId = pid) }
            }
            customSources.value = app.db.customSources()
            if (recorder.state.value.active.not() && recorder.restore()) {
                message.value = "Gravação anterior recuperada (pausada). Toque em Continuar ou Concluir."
            }
        }
        viewModelScope.launch { repo.changes.collect { reload() } }
        viewModelScope.launch {
            combine(features, settings.map { it.labels }.distinctUntilChanged(), map.styleReady) { fs, l, _ -> fs to l }
                .collect { (fs, l) -> map.setFeatures(fs, l) }
        }
        viewModelScope.launch { selected.collect { map.setSelection(it) } }
        viewModelScope.launch { recorder.state.collect { map.setRecording(it.segments) } }
        viewModelScope.launch {
            combine(fix, app.compass.azimuth) { f, az ->
                if (f?.speed != null && f.speed > 1.2f && f.bearing != null) f.bearing else az
            }.collect { heading.value = it }
        }
        viewModelScope.launch {
            combine(fix, heading, map.styleReady) { f, h, _ -> f to h }.collect { (f, h) ->
                map.setLocation(f, h)
                if (f != null) {
                    app.compass.setLocation(f.lat, f.lon, f.altitude ?: 0.0)
                    when (follow.value) {
                        FollowMode.FOLLOW -> map.follow(f.lat, f.lon, null)
                        FollowMode.HEADING -> map.follow(f.lat, f.lon, h?.toDouble())
                        FollowMode.OFF -> {}
                    }
                    checkNavigation(f)
                }
            }
        }
        viewModelScope.launch {
            combine(mode, map.styleReady) { m, _ -> m }.collect { renderMode(it) }
        }
        viewModelScope.launch {
            combine(nav, fix) { n, f -> n to f }.collect { (n, f) ->
                if (n is NavState.GoTo && f != null) map.setNavLine(Coord(f.lat, f.lon), n.target) else map.setNavLine(null, null)
            }
        }
        viewModelScope.launch {
            combine(settings.map { Triple(it.baseMap, it.overlays, it.googleKey + "|" + it.arcgisKey) }.distinctUntilChanged(), offline.areas, customSources) { a, b, c -> Triple(a, b, c) }
                .collect { applyBase() }
        }
        viewModelScope.launch { offline.messages.collect { m -> if (m != null) { message.value = m; offline.consumeMessage() } } }
        map.onClick = { lat, lon, x, y -> onMapClick(lat, lon, x, y) }
        map.onLongClick = { lat, lon -> onLongPress(lat, lon) }
        map.onUserGesture = { if (follow.value != FollowMode.OFF) follow.value = FollowMode.OFF }
    }

    private suspend fun reload() {
        val (ps, fs) = withContext(Dispatchers.IO) {
            val ps = repo.projects()
            ps to repo.features(ps.filter { it.visible }.map { it.id })
        }
        projects.value = ps
        features.value = fs
        if (selectedId.value != null && fs.none { it.id == selectedId.value }) selectedId.value = null
    }

    fun toast(s: String) { message.value = s }

    // ------------------------------------------------------------------ base cartográfica

    fun allBases(): List<BaseMap> = BaseMaps.builtIn + customSources.value.filter { !it.overlay }.map { BaseMaps.custom(it) }
    fun overlayBases(): List<BaseMap> = customSources.value.filter { it.overlay }.map { BaseMaps.custom(it) }

    private var baseJob: Job? = null

    fun applyBase() {
        baseJob?.cancel()
        baseJob = viewModelScope.launch {
            val s = settings.value
            val base = allBases().firstOrNull { it.id == s.baseMap } ?: BaseMaps.builtIn.first { it.id == "osm" }
            if (map.map == null) return@launch
            val overlays = overlayBases().filter { it.id in s.overlays }
            if (base.styleUrl != null) {
                map.setStyleUrl(base.styleUrl)
                return@launch
            }
            val tiles: List<String> = when {
                base.needsKey == "google" -> {
                    if (s.googleKey.isBlank()) {
                        message.value = "Para usar as camadas do Google, informe a chave da Map Tiles API em Configurações."
                        app.settings.update { it.copy(baseMap = "osm") }
                        return@launch
                    }
                    val session = withContext(Dispatchers.IO) { runCatching { BaseMaps.googleSession(app.settings, base.id) } }
                    session.getOrNull()?.let { BaseMaps.googleTiles(it, s.googleKey) } ?: run {
                        message.value = "Google indisponível: ${session.exceptionOrNull()?.message ?: "sem conexão"}"
                        app.settings.update { it.copy(baseMap = "osm") }
                        return@launch
                    }
                }
                else -> base.tiles ?: emptyList()
            }
            val json = BaseMaps.rasterStyle(base, tiles, overlays, offline.rasterLayersFor(base.id), br.geomaps.map.Glyphs.urlTemplate(app))
            map.setStyleJson(json)
        }
    }

    fun setBase(id: String) = app.settings.update { it.copy(baseMap = id) }
    fun toggleOverlay(id: String) = app.settings.update { s -> s.copy(overlays = if (id in s.overlays) s.overlays - id else s.overlays + id) }

    // ------------------------------------------------------------------ toques no mapa

    private fun onMapClick(lat: Double, lon: Double, x: Float, y: Float) {
        when (val m = mode.value) {
            Mode.Browse -> {
                val id = map.featureAt(x, y)
                selectedId.value = id
                if (id == null) profile.value = null
            }
            is Mode.Draw -> mode.value = m.copy(pts = m.pts + snap(lat, lon))
            is Mode.Measure -> mode.value = m.copy(pts = m.pts + snap(lat, lon))
            is Mode.EditVertices -> {
                val h = map.drawHandleAt(x, y)
                when {
                    h != null && h.first == "v" -> {
                        val i = h.second
                        mode.value = if (m.rangeMode && m.selected.size == 1) {
                            val a = m.selected.first()
                            m.copy(selected = (minOf(a, i)..maxOf(a, i)).toSet(), rangeMode = false)
                        } else if (m.selected == setOf(i)) m.copy(selected = emptySet()) else m.copy(selected = setOf(i))
                    }
                    h != null && h.first == "m" -> {
                        val i = h.second
                        val a = m.pts[i]; val b = m.pts[(i + 1) % m.pts.size]
                        val mid = Coord((a.lat + b.lat) / 2, (a.lon + b.lon) / 2, if (a.ele != null && b.ele != null) (a.ele + b.ele) / 2 else null)
                        mode.value = m.copy(pts = TrackOps.insertVertex(m.pts, i + 1, mid), selected = setOf(i + 1))
                    }
                    m.selected.size == 1 -> {
                        // move o vértice selecionado para o ponto tocado
                        val i = m.selected.first()
                        mode.value = m.copy(pts = TrackOps.moveVertex(m.pts, i, snap(lat, lon, exclude = m.featureId)))
                    }
                }
            }
            is Mode.Split -> {
                val h = map.drawHandleAt(x, y)
                if (h != null && h.first == "v" && h.second > 0 && h.second < m.pts.size - 1) {
                    val i = h.second
                    mode.value = m.copy(cuts = if (i in m.cuts) m.cuts - i else m.cuts + i)
                } else toast("Toque num vértice interno da trilha para marcar o ponto de divisão")
            }
            is Mode.PickArea -> {
                val c = Coord(lat, lon)
                mode.value = if (!m.free) {
                    if (m.pts.size >= 2) m.copy(pts = listOf(c)) else m.copy(pts = m.pts + c)
                } else m.copy(pts = m.pts + c)
            }
            Mode.Freehand -> {}
        }
    }

    private fun onLongPress(lat: Double, lon: Double) {
        if (mode.value != Mode.Browse) return
        newWaypoint(Coord(lat, lon, app.dem.sample(lat, lon)))
    }

    /** Ajusta a coordenada ao vértice mais próximo dentro da tolerância (snapping). */
    fun snap(lat: Double, lon: Double, exclude: Long? = null): Coord {
        val s = settings.value
        val ele = app.dem.sample(lat, lon)
        if (!s.snapping) return Coord(lat, lon, ele)
        val tol = s.snapPx * app.resources.displayMetrics.density * map.metersPerPixel()
        val local = GeoMath.Local(Coord(lat, lon))
        var best: Coord? = null; var bestD = tol
        val mm = mode.value
        val own = when (mm) { is Mode.Draw -> mm.pts; else -> emptyList() }
        for (c in own) {
            val d = Math.hypot(local.x(c), local.y(c)); if (d < bestD) { bestD = d; best = c }
        }
        for (f in features.value) {
            if (f.id == exclude) continue
            for (c in f.geom.allCoords()) {
                if (abs(c.lat - lat) > 0.05 || abs(c.lon - lon) > 0.05) continue
                val d = Math.hypot(local.x(c), local.y(c))
                if (d < bestD) { bestD = d; best = c }
            }
        }
        return best?.copy(time = null, acc = null) ?: Coord(lat, lon, ele)
    }

    private fun renderMode(m: Mode) {
        map.setDraw(emptyList(), false); map.setMeasure(emptyList(), false); map.setAreaPreview(null)
        when (m) {
            is Mode.Draw -> map.setDraw(m.pts, m.type == GeomType.POLYGON)
            is Mode.EditVertices -> map.setDraw(m.pts, m.closed, m.selected, midpoints = !m.isPoint)
            is Mode.Measure -> map.setMeasure(m.pts, m.area)
            is Mode.Split -> map.setDraw(m.pts, false, m.cuts)
            is Mode.PickArea -> map.setAreaPreview(pickRing(m))
            else -> {}
        }
    }

    fun pickRing(m: Mode.PickArea): List<Coord>? {
        if (m.free) return m.pts
        if (m.pts.size < 2) return m.pts
        val a = m.pts[0]; val b = m.pts[1]
        return listOf(Coord(a.lat, a.lon), Coord(a.lat, b.lon), Coord(b.lat, b.lon), Coord(b.lat, a.lon))
    }

    // ------------------------------------------------------------------ desfazer / refazer

    private suspend fun applyEdit(before: List<GeoFeature>, after: List<GeoFeature>) = withContext(Dispatchers.IO) {
        val afterIds = after.map { it.id }.toSet()
        for (b in before) if (b.id !in afterIds) app.db.deleteFeature(b.id)
        for (a in after) {
            if (app.db.feature(a.id) != null) app.db.updateFeature(a) else app.db.insertFeature(a)
        }
        repo.notifyChanged()
    }

    /** Registra uma alteração (ids novos já atribuídos em [after]). */
    private fun pushEdit(label: String, before: List<GeoFeature>, after: List<GeoFeature>) {
        undoStack.addLast(Edit(label, before, after))
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        canUndo.value = true; canRedo.value = false
    }

    fun undo() {
        val e = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            applyEdit(e.after, e.before)
            redoStack.addLast(e)
            canUndo.value = undoStack.isNotEmpty(); canRedo.value = true
            toast("Desfeito: ${e.label}")
        }
    }

    fun redo() {
        val e = redoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            applyEdit(e.before, e.after)
            undoStack.addLast(e)
            canUndo.value = true; canRedo.value = redoStack.isNotEmpty()
            toast("Refeito: ${e.label}")
        }
    }

    /** Altera/insere/remove elementos com registro para desfazer. */
    fun commit(label: String, before: List<GeoFeature>, after: List<GeoFeature>, onDone: ((List<GeoFeature>) -> Unit)? = null) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val afterIds = after.map { it.id }.toSet()
                for (b in before) if (b.id !in afterIds) app.db.deleteFeature(b.id)
                val out = after.map { a ->
                    if (a.id > 0 && app.db.feature(a.id) != null) { app.db.updateFeature(a); a }
                    else a.copy(id = app.db.insertFeature(a.copy(id = 0)))
                }
                out.map { it.projectId }.distinct().forEach { app.db.touchProject(it) }
                repo.notifyChanged()
                out
            }
            pushEdit(label, before, saved)
            onDone?.invoke(saved)
        }
    }

    // ------------------------------------------------------------------ projetos

    fun setActive(id: Long) = app.settings.update { it.copy(activeProjectId = id) }

    fun activeProjectId(): Long = activeProject.value?.id ?: settings.value.activeProjectId

    fun createProject(name: String, makeActive: Boolean = true, then: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { repo.createProject(name.ifBlank { "Novo projeto" }) }
            if (makeActive) setActive(id)
            then?.invoke(id)
        }
    }

    fun io(msg: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = msg
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message.value = "Erro: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy.value = null
            }
        }
    }

    fun setVisible(id: Long, v: Boolean) = io { repo.setVisible(id, v) }
    fun renameProject(id: Long, name: String) = io { repo.projects().firstOrNull { it.id == id }?.let { repo.updateProject(it.copy(name = name)) } }
    fun updateProject(p: Project) = io { repo.updateProject(p) }
    fun duplicateProject(id: Long) = io("Duplicando…") { repo.duplicateProject(id); message.value = "Projeto duplicado" }
    fun mergeProjects(target: Long, others: List<Long>) = io("Mesclando…") { repo.mergeProjects(target, others); message.value = "Projetos mesclados" }
    fun deleteProject(id: Long) = io {
        repo.deleteProject(id)
        if (settings.value.activeProjectId == id) {
            val other = repo.projects().firstOrNull()?.id ?: repo.ensureDefaultProject()
            app.settings.update { it.copy(activeProjectId = other) }
        }
    }

    fun snapshot(projectId: Long, label: String) = io { repo.snapshot(projectId, label); message.value = "Versão salva" }
    fun restoreVersion(projectId: Long, versionId: Long) = io("Restaurando…") { repo.restoreVersion(projectId, versionId); message.value = "Versão restaurada" }

    fun zoomToProject(id: Long) {
        viewModelScope.launch {
            val fs = withContext(Dispatchers.IO) { repo.features(listOf(id)) }
            map.fit(fs.flatMap { it.geom.allCoords() })
        }
    }

    // ------------------------------------------------------------------ importação

    fun fileName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        return name
    }

    fun importUri(uri: Uri, targetProject: Long? = null) {
        io("Lendo arquivo…") {
            val name = fileName(uri)
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Não foi possível ler o arquivo")
            if (name.endsWith(".geomaps", true)) {
                val n = Backup.restore(repo, bytes)
                message.value = "Backup restaurado: $n projeto(s)"
                return@io
            }
            val r = repo.parse(name, bytes, settings.value.coordPrefs)
            dialog.value = Dialog.Import(r, targetProject)
        }
    }

    fun confirmImport(result: ImportResult, selected: List<GeoFeature>, projectId: Long?, newName: String?) {
        io("Importando…") {
            val pid = projectId ?: repo.createProject(newName?.ifBlank { null } ?: result.sourceName.substringBeforeLast('.'))
            val n = repo.saveImport(result, selected, pid)
            if (projectId == null) app.settings.update { it.copy(activeProjectId = pid) }
            repo.projects().firstOrNull { it.id == pid }?.let { if (!it.visible) repo.setVisible(pid, true) }
            message.value = "$n elemento(s) importado(s)"
            withContext(Dispatchers.Main) {
                map.invalidateIcons()
                map.fit(selected.flatMap { it.geom.allCoords() })
            }
        }
    }

    // ------------------------------------------------------------------ exportação / compartilhamento

    fun export(projectId: Long?, featureIds: List<Long>?, format: ExportFormat, share: Boolean, title: String) {
        io("Gerando arquivo…") {
            val feats = when {
                featureIds != null -> featureIds.mapNotNull { repo.feature(it) }
                projectId != null -> repo.features(listOf(projectId))
                else -> features.value
            }
            if (feats.isEmpty()) throw IllegalStateException("Nada para exportar")
            if (format == ExportFormat.CSV && feats.none { it.type == GeomType.POINT }) throw IllegalStateException("CSV exporta apenas pontos")
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "geomaps" }
            val bytes = repo.export(feats, format, title, settings.value.coordPrefs)
            exportRequest.value = ExportRequest(bytes, "$safe.${format.ext}", format.mime, share, format)
        }
    }

    // ------------------------------------------------------------------ waypoints e elementos

    fun currentCoord(): Coord {
        val f = fix.value
        return if (f != null) Coord(f.lat, f.lon, f.altitude, f.time, f.accuracy?.toDouble())
        else map.center().let { Coord(it.lat, it.lon, app.dem.sample(it.lat, it.lon)) }
    }

    fun newWaypoint(at: Coord? = null) {
        val c = at ?: currentCoord()
        val n = features.value.count { it.type == GeomType.POINT && it.projectId == activeProjectId() } + 1
        dialog.value = Dialog.Waypoint(GeoFeature(projectId = activeProjectId(), name = "Ponto $n", geom = Geom.Pt(c)))
    }

    fun saveFeature(f: GeoFeature) {
        val existing = if (f.id > 0) features.value.firstOrNull { it.id == f.id } else null
        commit(if (existing == null) "Criar ${f.name}" else "Editar ${f.name}", listOfNotNull(existing), listOf(f)) { saved ->
            if (existing == null) selectedId.value = saved.firstOrNull()?.id
        }
        dialog.value = null
    }

    fun deleteFeature(id: Long) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        commit("Excluir ${f.name}", listOf(f), emptyList())
        selectedId.value = null
        profile.value = null
    }

    fun duplicateFeature(id: Long) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        commit("Duplicar ${f.name}", emptyList(), listOf(f.copy(id = 0, name = f.name + " (cópia)", created = System.currentTimeMillis())))
    }

    fun moveToProject(id: Long, projectId: Long) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        commit("Mover ${f.name}", listOf(f), listOf(f.copy(projectId = projectId)))
    }

    fun zoomTo(f: GeoFeature) = map.fit(f.geom.allCoords())

    fun select(id: Long, zoom: Boolean = true) {
        selectedId.value = id
        screen.value = Screen.NONE
        if (zoom) features.value.firstOrNull { it.id == id }?.let { zoomTo(it) }
    }

    fun categories(): List<String> = features.value.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    fun addPhoto(uri: Uri, onResult: (String, Coord?) -> Unit) {
        io("Copiando foto…") {
            val r = app.media.importPhoto(uri) ?: throw IllegalStateException("Não foi possível ler a foto")
            withContext(Dispatchers.Main) { onResult(r.first, r.second) }
        }
    }

    fun cameraPhotoFile(): java.io.File {
        val dir = java.io.File(app.cacheDir, "camera").apply { mkdirs() }
        return java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
    }

    fun takeCameraPhoto(file: java.io.File, onResult: (String, Coord?) -> Unit) {
        io {
            if (!file.exists() || file.length() == 0L) return@io
            val c = currentCoord()
            app.media.writeExif(file, c)
            val name = app.media.savePhoto(file.readBytes(), "jpg")
            file.delete()
            withContext(Dispatchers.Main) { onResult(name, c) }
        }
    }

    // ------------------------------------------------------------------ desenho manual

    fun startDraw(type: GeomType) {
        selectedId.value = null
        mode.value = Mode.Draw(type)
        toast(if (type == GeomType.LINE) "Toque no mapa para adicionar vértices da trilha" else "Toque no mapa para adicionar vértices da área")
    }

    fun drawAddCenter() {
        val m = mode.value
        val c = map.center()
        when (m) {
            is Mode.Draw -> mode.value = m.copy(pts = m.pts + snap(c.lat, c.lon))
            is Mode.Measure -> mode.value = m.copy(pts = m.pts + snap(c.lat, c.lon))
            is Mode.PickArea -> onMapClickCoord(c)
            else -> {}
        }
    }

    private fun onMapClickCoord(c: Coord) {
        val m = mode.value as? Mode.PickArea ?: return
        mode.value = if (!m.free) (if (m.pts.size >= 2) m.copy(pts = listOf(c)) else m.copy(pts = m.pts + c)) else m.copy(pts = m.pts + c)
    }

    fun drawAddCoord(c: Coord) {
        when (val m = mode.value) {
            is Mode.Draw -> mode.value = m.copy(pts = m.pts + c.copy(ele = c.ele ?: app.dem.sample(c.lat, c.lon)))
            is Mode.EditVertices -> {
                val idx = m.selected.maxOrNull()?.plus(1) ?: m.pts.size
                mode.value = if (m.isPoint) m.copy(pts = listOf(c)) else m.copy(pts = TrackOps.insertVertex(m.pts, idx, c), selected = setOf(idx))
            }
            is Mode.Measure -> mode.value = m.copy(pts = m.pts + c)
            else -> {}
        }
        map.flyTo(c.lat, c.lon)
    }

    fun drawUndo() {
        when (val m = mode.value) {
            is Mode.Draw -> mode.value = m.copy(pts = m.pts.dropLast(1))
            is Mode.Measure -> mode.value = m.copy(pts = m.pts.dropLast(1))
            is Mode.PickArea -> mode.value = m.copy(pts = m.pts.dropLast(1))
            else -> {}
        }
    }

    fun finishDraw() {
        val m = mode.value as? Mode.Draw ?: return
        val min = if (m.type == GeomType.POLYGON) 3 else 2
        if (m.pts.size < min) { toast("Adicione pelo menos $min vértices"); return }
        val n = features.value.count { it.type == m.type && it.projectId == activeProjectId() } + 1
        val geom = if (m.type == GeomType.POLYGON) Geom.Poly.of(m.pts) else Geom.Line.of(m.pts)
        val style = if (m.type == GeomType.POLYGON) FeatureStyle(color = 0xFFFB8C00.toInt(), width = 2.5f, fill = 0x44FB8C00)
        else FeatureStyle(color = 0xFFE53935.toInt(), width = 4f)
        mode.value = Mode.Browse
        dialog.value = Dialog.FeatureProps(GeoFeature(projectId = activeProjectId(), name = (if (m.type == GeomType.POLYGON) "Área " else "Trilha ") + n, geom = geom, style = style))
    }

    fun cancelMode() {
        mode.value = Mode.Browse
    }

    // ------------------------------------------------------------------ edição de vértices

    fun startEditVertices(id: Long) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        mode.value = when (val g = f.geom) {
            is Geom.Pt -> Mode.EditVertices(id, 0, listOf(g.c), false, setOf(0), isPoint = true)
            is Geom.Line -> Mode.EditVertices(id, 0, g.parts[0], false)
            is Geom.Poly -> Mode.EditVertices(id, 0, g.outer, true)
        }
        toast(if (f.geom is Geom.Pt) "Toque no mapa para mover o ponto" else "Toque num vértice para selecionar; toque no mapa para movê-lo; círculos brancos inserem vértices")
    }

    fun editNextPart() {
        val m = mode.value as? Mode.EditVertices ?: return
        val f = features.value.firstOrNull { it.id == m.featureId } ?: return
        val parts: List<List<Coord>> = when (val g = f.geom) { is Geom.Line -> g.parts; is Geom.Poly -> g.polys.flatten(); else -> return }
        if (parts.size < 2) { toast("Este elemento tem um único segmento"); return }
        commitVertices(keepEditing = true)
        val next = (m.part + 1) % parts.size
        mode.value = m.copy(part = next, pts = parts[next], selected = emptySet())
    }

    fun vertexMoveToCenter() {
        val m = mode.value as? Mode.EditVertices ?: return
        val i = m.selected.singleOrNull() ?: run { toast("Selecione um vértice"); return }
        val c = map.center()
        mode.value = m.copy(pts = TrackOps.moveVertex(m.pts, i, snap(c.lat, c.lon, m.featureId)))
    }

    fun vertexInsertAtCenter() {
        val m = mode.value as? Mode.EditVertices ?: return
        if (m.isPoint) return vertexMoveToCenter()
        val c = map.center()
        val idx = m.selected.maxOrNull()?.plus(1) ?: m.pts.size
        mode.value = m.copy(pts = TrackOps.insertVertex(m.pts, idx, snap(c.lat, c.lon, m.featureId)), selected = setOf(idx))
    }

    fun vertexDelete() {
        val m = mode.value as? Mode.EditVertices ?: return
        if (m.selected.isEmpty()) { toast("Selecione um ou mais vértices"); return }
        val min = if (m.closed) 3 else 2
        val rest = TrackOps.deleteVertices(m.pts, m.selected)
        if (rest.size < min) { toast("A geometria precisa de pelo menos $min vértices"); return }
        mode.value = m.copy(pts = rest, selected = emptySet())
    }

    fun vertexRange() {
        val m = mode.value as? Mode.EditVertices ?: return
        if (m.selected.size != 1) { toast("Selecione o primeiro vértice e depois toque em Intervalo"); return }
        mode.value = m.copy(rangeMode = true)
        toast("Agora toque no último vértice do intervalo")
    }

    fun vertexSelectAll() {
        val m = mode.value as? Mode.EditVertices ?: return
        mode.value = m.copy(selected = if (m.selected.size == m.pts.size) emptySet() else m.pts.indices.toSet())
    }

    fun commitVertices(keepEditing: Boolean = false) {
        val m = mode.value as? Mode.EditVertices ?: return
        val f = features.value.firstOrNull { it.id == m.featureId } ?: return
        val g: Geom = when (val og = f.geom) {
            is Geom.Pt -> Geom.Pt(m.pts.first())
            is Geom.Line -> Geom.Line(og.parts.toMutableList().also { it[m.part] = m.pts })
            is Geom.Poly -> {
                var k = 0
                Geom.Poly(og.polys.map { poly -> poly.map { r -> if (k++ == m.part) m.pts else r } })
            }
        }
        if (g != f.geom) commit("Editar vértices de ${f.name}", listOf(f), listOf(f.copy(geom = g)))
        if (!keepEditing) mode.value = Mode.Browse
    }

    // ------------------------------------------------------------------ operações de trilha

    fun reverse(id: Long, mode: TrackOps.TimeMode) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        commit("Inverter ${f.name}", listOf(f), listOf(f.copy(geom = TrackOps.reverseGeom(f.geom, mode))))
        toast("Sentido invertido")
    }

    fun simplify(id: Long, tol: Double) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        val g = TrackOps.simplifyGeom(f.geom, tol)
        commit("Simplificar ${f.name}", listOf(f), listOf(f.copy(geom = g)))
        toast("Vértices: ${f.geom.allCoords().size} → ${g.allCoords().size}")
    }

    fun filterGps(id: Long, maxAcc: Double?, maxSpeedKmh: Double?, maxJump: Double?) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        val g = f.geom as? Geom.Line ?: return
        val ng = Geom.Line(g.parts.map { TrackOps.filterGps(it, maxAcc, maxSpeedKmh?.div(3.6), maxJump) }.filter { it.size >= 2 })
        if (ng.parts.isEmpty()) { toast("O filtro removeria todos os pontos"); return }
        commit("Filtrar ${f.name}", listOf(f), listOf(f.copy(geom = ng)))
        toast("Pontos: ${g.allCoords().size} → ${ng.allCoords().size}")
    }

    fun merge(baseId: Long, items: List<Pair<Long, Boolean>>, connect: Boolean, keepOriginals: Boolean) {
        val fs = items.mapNotNull { (id, rev) -> features.value.firstOrNull { it.id == id }?.let { it to rev } }
        if (fs.size < 2) { toast("Escolha pelo menos duas trilhas"); return }
        val base = features.value.firstOrNull { it.id == baseId } ?: fs.first().first
        val mergeItems = fs.flatMap { (f, rev) ->
            val parts = (f.geom as? Geom.Line)?.parts ?: emptyList()
            val ordered = if (rev) parts.reversed() else parts
            ordered.map { TrackOps.MergeItem(it, rev) }
        }
        val g = TrackOps.merge(mergeItems, connect)
        val merged = base.copy(id = 0, name = base.name + " (mesclada)", geom = g, created = System.currentTimeMillis())
        val removed = if (keepOriginals) emptyList() else fs.map { it.first }
        commit("Mesclar trilhas", removed, listOf(merged)) { saved -> selectedId.value = saved.firstOrNull()?.id }
        dialog.value = null
    }

    fun startSplit(id: Long) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        val g = f.geom as? Geom.Line ?: return
        if (g.parts.size > 1) {
            // separa os segmentos em elementos independentes
            commit("Separar segmentos de ${f.name}", listOf(f), g.parts.mapIndexed { i, p -> f.copy(id = if (i == 0) f.id else 0, name = "${f.name} (${i + 1})", geom = Geom.Line.of(p)) })
            toast("Segmentos separados em ${g.parts.size} trilhas")
            return
        }
        mode.value = Mode.Split(id, g.main)
        toast("Toque nos vértices onde a trilha deve ser dividida e depois em Dividir")
    }

    fun confirmSplit() {
        val m = mode.value as? Mode.Split ?: return
        val f = features.value.firstOrNull { it.id == m.featureId } ?: return
        if (m.cuts.isEmpty()) { toast("Marque pelo menos um ponto de divisão"); return }
        val pieces = TrackOps.splitMany(m.pts, m.cuts.toList())
        val after = pieces.mapIndexed { i, p -> f.copy(id = if (i == 0) f.id else 0, name = "${f.name} (${i + 1})", geom = Geom.Line.of(p)) }
        commit("Dividir ${f.name}", listOf(f), after)
        mode.value = Mode.Browse
        toast("Trilha dividida em ${pieces.size} partes")
    }

    fun buffer(id: Long, distance: Double) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        io("Calculando buffer…") {
            val poly = br.geomaps.data.GeoBuffer.compute(f.geom, distance)
            withContext(Dispatchers.Main) {
                commit("Buffer de ${f.name}", emptyList(), listOf(GeoFeature(
                    projectId = f.projectId, name = "Buffer ${distance.toInt()} m — ${f.name}", geom = poly,
                    style = FeatureStyle(color = 0xFF8E24AA.toInt(), width = 2f, fill = 0x338E24AA),
                )))
            }
        }
    }

    // ------------------------------------------------------------------ perfil de elevação

    fun showProfile(id: Long, useDem: Boolean? = null) {
        val f = features.value.firstOrNull { it.id == id } ?: return
        val parts = when (val g = f.geom) { is Geom.Line -> g.parts; is Geom.Poly -> g.polys.map { it.first() + it.first().first() }; else -> return }
        viewModelScope.launch {
            busy.value = "Calculando perfil…"
            val all = parts.flatten()
            val demAvail = withContext(Dispatchers.IO) { all.isNotEmpty() && app.dem.hasData(all[all.size / 2].lat, all[all.size / 2].lon) }
            val hasEle = all.any { it.ele != null }
            val preferDem = useDem ?: when (f.eleSource) {
                "dem" -> true
                "gps" -> false
                else -> if (f.recorded) settings.value.eleSourceDefault == "dem" else !hasEle
            }
            val dem = preferDem && demAvail
            val p = withContext(Dispatchers.IO) {
                Profile.build(parts, dem, { la, lo -> app.dem.sample(la, lo) }, maxStep = if (dem) 30.0 else 0.0)
            }
            busy.value = null
            if (p == null) toast(if (!demAvail) "Sem altitude: baixe o MDE da região em Mapas offline" else "Não foi possível calcular o perfil")
            profile.value = ProfileState(id, f.name, p, dem, demAvail, null, f.recorded || hasEle)
            map.setProfileMarker(null)
        }
    }

    fun setProfileSource(useDem: Boolean) {
        val p = profile.value ?: return
        val f = features.value.firstOrNull { it.id == p.featureId } ?: return
        if (f.recorded) commit("Fonte de altitude", listOf(f), listOf(f.copy(eleSource = if (useDem) "dem" else "gps")))
        showProfile(p.featureId, useDem)
    }

    fun profileHighlight(i: Int?) {
        val p = profile.value ?: return
        profile.value = p.copy(highlight = i)
        map.setProfileMarker(i?.let { p.profile?.coords?.getOrNull(it) })
    }

    fun closeProfile() { profile.value = null; map.setProfileMarker(null) }

    // ------------------------------------------------------------------ navegação

    fun goTo(f: GeoFeature) {
        val c = (f.geom as? Geom.Pt)?.c ?: GeoMath.centroid(f.geom.allCoords())
        nav.value = NavState.GoTo(c, f.name)
        hub.acquire("nav")
    }

    fun goToCoord(c: Coord, name: String) { nav.value = NavState.GoTo(c, name); hub.acquire("nav") }

    fun followTrack(f: GeoFeature) {
        val parts = (f.geom as? Geom.Line)?.parts ?: return
        nav.value = NavState.FollowTrack(f.id, f.name, parts)
        hub.acquire("nav")
        if (follow.value == FollowMode.OFF) follow.value = FollowMode.FOLLOW
    }

    fun stopNav() { nav.value = null; hub.release("nav") }

    private fun checkNavigation(f: Fix) {
        val n = nav.value as? NavState.FollowTrack ?: return
        val near = GeoMath.nearestOnLine(Coord(f.lat, f.lon), n.line) ?: return
        if (near.dist > settings.value.deviationAlertM && System.currentTimeMillis() - lastAlert > 20000) {
            lastAlert = System.currentTimeMillis()
            vibrate()
            toast("Fora da trilha: ${near.dist.toInt()} m")
        }
    }

    private fun vibrate() {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        }
        runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90).startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400)
        }
    }

    // ------------------------------------------------------------------ gravação

    fun startRecording() {
        if (!hub.hasPermission()) { toast("Permita o acesso à localização"); return }
        recorder.maxAccuracy = settings.value.recMaxAccuracyM.toFloat()
        recorder.start()
        TrackRecordingService.start(app)
        if (follow.value == FollowMode.OFF) follow.value = FollowMode.FOLLOW
    }

    fun pauseRecording() = recorder.pause()
    fun resumeRecording() {
        recorder.resume()
        TrackRecordingService.start(app)
    }

    fun finishRecording(name: String, description: String, eleSource: String, projectId: Long) {
        val segs = recorder.finish()
        TrackRecordingService.stop(app)
        dialog.value = null
        if (segs.isEmpty()) { toast("Trilha descartada: poucos pontos"); return }
        commit("Gravar ${name}", emptyList(), listOf(GeoFeature(
            projectId = projectId, name = name, description = description, geom = Geom.Line(segs),
            style = FeatureStyle(color = 0xFFD500F9.toInt(), width = 4f), recorded = true, eleSource = eleSource,
        ))) { saved -> saved.firstOrNull()?.let { selectedId.value = it.id } }
    }

    fun discardRecording() {
        recorder.discard()
        TrackRecordingService.stop(app)
        dialog.value = null
    }

    fun defaultTrackName(): String = "Trilha " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(System.currentTimeMillis())

    // ------------------------------------------------------------------ medição e área offline

    fun startMeasure() { selectedId.value = null; mode.value = Mode.Measure() }

    fun startPickArea(purpose: String, free: Boolean) {
        screen.value = Screen.NONE
        mode.value = Mode.PickArea(purpose, free)
        toast(if (free) "Toque no mapa para desenhar o contorno da área" else "Toque em dois cantos opostos do retângulo")
    }

    fun pickVisibleArea(purpose: String) {
        val m = map.map ?: return
        val r = m.projection.visibleRegion
        val ring = listOf(
            Coord(r.latLngBounds.latitudeNorth, r.latLngBounds.longitudeWest), Coord(r.latLngBounds.latitudeNorth, r.latLngBounds.longitudeEast),
            Coord(r.latLngBounds.latitudeSouth, r.latLngBounds.longitudeEast), Coord(r.latLngBounds.latitudeSouth, r.latLngBounds.longitudeWest),
        )
        screen.value = Screen.NONE
        dialog.value = Dialog.OfflineDownload(purpose, ring, false)
    }

    fun confirmPickArea() {
        val m = mode.value as? Mode.PickArea ?: return
        val ring = pickRing(m) ?: emptyList()
        if (ring.size < 3) { toast("Defina a área primeiro"); return }
        mode.value = Mode.Browse
        dialog.value = Dialog.OfflineDownload(m.purpose, ring, m.free)
    }

    fun startOfflineDownload(purpose: String, name: String, ring: List<Coord>, free: Boolean, baseId: String, minZ: Int, maxZ: Int, demSrc: String) {
        val b = GeoMath.bounds(ring) ?: return
        dialog.value = null
        when (purpose) {
            "dem" -> offline.startDem(name, b, demSrc)
            else -> {
                val base = allBases().firstOrNull { it.id == baseId } ?: return
                if (base.styleUrl != null) {
                    offline.startVector(name, base.styleUrl, b, if (free) ring else null, minZ, maxZ)
                } else {
                    val template = when (base.id) {
                        "esri" -> {
                            val key = settings.value.arcgisKey
                            if (key.isBlank()) { toast("Informe a chave da ArcGIS (API key) em Configurações para baixar o Esri World Imagery"); return }
                            "https://tiledbasemaps.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?token=$key"
                        }
                        else -> base.tiles?.firstOrNull() ?: return
                    }
                    offline.startRaster(name, base, template, b, if (free) ring else null, minZ, maxZ)
                }
            }
        }
        screen.value = Screen.OFFLINE
        toast("Download iniciado")
    }

    // ------------------------------------------------------------------ fontes personalizadas

    fun saveCustomSource(s: Db.CustomSource) = io {
        app.db.saveCustomSource(s)
        customSources.value = app.db.customSources()
    }

    fun deleteCustomSource(id: Long) = io {
        app.db.deleteCustomSource(id)
        customSources.value = app.db.customSources()
        if (settings.value.baseMap == "c_$id") app.settings.update { it.copy(baseMap = "osm") }
    }

    // ------------------------------------------------------------------ mão livre

    fun saveFreehandAsLines(strokes: List<List<Pair<Float, Float>>>, color: Int) {
        val lines = strokes.filter { it.size >= 2 }.mapNotNull { s ->
            val cs = s.mapNotNull { (x, y) -> map.fromScreen(x, y) }
            TrackOps.simplify(cs, map.metersPerPixel() * 1.5).takeIf { it.size >= 2 }
        }
        if (lines.isEmpty()) { toast("Nada desenhado"); return }
        commit("Desenho à mão livre", emptyList(), lines.mapIndexed { i, l ->
            GeoFeature(projectId = activeProjectId(), name = "Esboço ${i + 1}", geom = Geom.Line.of(l), style = FeatureStyle(color = color, width = 3f), category = "Esboço")
        })
        toast("${lines.size} esboço(s) salvo(s) como linha")
    }

    fun shareIntent(file: java.io.File, mime: String): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartilhar")
    }

    fun formatCoord(lat: Double, lon: Double) = CoordFormat.format(lat, lon, settings.value.coordPrefs)
}
