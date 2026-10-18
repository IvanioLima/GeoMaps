package br.geomaps.data

import android.content.Context
import br.geomaps.core.Codec
import br.geomaps.core.CoordPrefs
import br.geomaps.core.CsvIO
import br.geomaps.core.Crs
import br.geomaps.core.DatumChoice
import br.geomaps.core.Dxf
import br.geomaps.core.ExportFormat
import br.geomaps.core.FeatureStyle
import br.geomaps.core.Formats
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeoJsonIO
import br.geomaps.core.Gpx
import br.geomaps.core.ImportResult
import br.geomaps.core.Json
import br.geomaps.core.Kml
import br.geomaps.core.Project
import br.geomaps.core.Shapefile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Operações de alto nível sobre projetos e elementos. Todas devem ser chamadas fora da thread principal. */
class Repo(private val context: Context, val db: Db, val media: MediaStore, val icons: IconFactory) {
    private val _changes = MutableStateFlow(0L)
    /** Incrementa a cada alteração (a interface recarrega quando muda). */
    val changes: StateFlow<Long> = _changes
    fun notifyChanged() { _changes.value = _changes.value + 1 }

    fun projects() = db.projects()

    fun ensureDefaultProject(): Long {
        val ps = db.projects()
        if (ps.isNotEmpty()) return ps.first().id
        val id = db.insertProject(Project(name = "Meu mapa"))
        notifyChanged()
        return id
    }

    fun createProject(name: String): Long = db.insertProject(Project(name = name)).also { notifyChanged() }

    fun updateProject(p: Project) { db.updateProject(p); notifyChanged() }

    fun deleteProject(id: Long) { db.deleteProject(id); notifyChanged() }

    fun setVisible(id: Long, visible: Boolean) {
        db.projects().firstOrNull { it.id == id }?.let { db.updateProject(it.copy(visible = visible)) }
        notifyChanged()
    }

    fun duplicateProject(id: Long): Long {
        val p = db.projects().first { it.id == id }
        val nid = db.insertProject(p.copy(id = 0, name = p.name + " (cópia)", created = System.currentTimeMillis()))
        db.insertFeatures(db.features(listOf(id)).map { it.copy(id = 0, projectId = nid) })
        notifyChanged()
        return nid
    }

    /** Mescla [others] dentro de [target] (os projetos de origem são removidos). */
    fun mergeProjects(target: Long, others: List<Long>) {
        snapshot(target, "Antes de mesclar projetos")
        val tp = db.projects().first { it.id == target }
        val form = (tp.form + others.flatMap { o -> db.projects().firstOrNull { it.id == o }?.form ?: emptyList() }).distinctBy { it.name }
        db.updateProject(tp.copy(form = form))
        for (o in others) if (o != target) { db.moveFeatures(o, target); db.deleteProject(o) }
        notifyChanged()
    }

    fun features(projectIds: Collection<Long>) = db.features(projectIds)
    fun feature(id: Long) = db.feature(id)

    fun insert(f: GeoFeature): Long = db.insertFeature(f).also { db.touchProject(f.projectId); notifyChanged() }
    fun update(f: GeoFeature) { db.updateFeature(f); db.touchProject(f.projectId); notifyChanged() }
    fun delete(id: Long) { db.deleteFeature(id); notifyChanged() }
    fun insertAll(list: List<GeoFeature>): List<Long> = db.insertFeatures(list).also { notifyChanged() }

    // ------------------------------------------------------------------ versões

    fun snapshotJson(projectId: Long): Pair<String, Int> {
        val fs = db.features(listOf(projectId))
        val p = db.projects().firstOrNull { it.id == projectId }
        return Json.write(mapOf(
            "project" to p?.name, "form" to Codec.formToJson(p?.form ?: emptyList()),
            "features" to fs.map { Codec.featureToMap(it) },
        )) to fs.size
    }

    fun snapshot(projectId: Long, label: String) {
        val (json, n) = snapshotJson(projectId)
        db.insertVersion(projectId, label, json, n)
    }

    fun restoreVersion(projectId: Long, versionId: Long) {
        val s = db.versionSnapshot(versionId) ?: return
        snapshot(projectId, "Antes de restaurar versão")
        val feats = Db.parseSnapshot(s, projectId)
        db.writableDatabase.delete("features", "project_id=?", arrayOf(projectId.toString()))
        db.insertFeatures(feats.map { it.copy(id = 0, projectId = projectId) })
        notifyChanged()
    }

    // ------------------------------------------------------------------ importação

    fun parse(name: String, bytes: ByteArray, prefs: CoordPrefs): ImportResult {
        if (Formats.detect(name, bytes) == "gpkg") {
            val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.gpkg")
            tmp.writeBytes(bytes)
            try { return Gpkg.read(tmp, name) } finally { tmp.delete() }
        }
        return Formats.read(name, bytes, prefs)
    }

    /**
     * Grava os elementos escolhidos no projeto. Ícones e fotos embutidos (KMZ) são copiados para o app;
     * ícones http (padrão Google Earth) são baixados quando há internet.
     */
    fun saveImport(result: ImportResult, selected: List<GeoFeature>, projectId: Long): Int {
        val iconMap = HashMap<String, String>()
        val photoMap = HashMap<String, String>()
        val list = selected.map { f ->
            var style = f.style
            val ik = style.icon
            if (ik.startsWith("href:")) {
                val key = iconMap.getOrPut(ik) {
                    val bytes = result.resources[ik]
                    when {
                        bytes != null -> media.saveIcon(bytes)
                        ik.removePrefix("href:").startsWith("http", true) -> media.downloadIcon(ik.removePrefix("href:"))
                        else -> null
                    } ?: FeatureStyle.DEFAULT_ICON
                }
                style = style.copy(icon = key, color = if (key == FeatureStyle.DEFAULT_ICON && style.color == -1) FeatureStyle.DEFAULT_LINE else style.color)
            }
            val photos = f.photos.mapNotNull { p ->
                if (!p.startsWith("href:")) return@mapNotNull p
                photoMap.getOrPut(p) {
                    val b = result.resources["photo:" + p.removePrefix("href:")] ?: return@mapNotNull null
                    media.savePhoto(b, p.substringAfterLast('.', "jpg").take(4))
                }
            }
            f.copy(id = 0, projectId = projectId, style = style, photos = photos)
        }
        db.insertFeatures(list)
        db.touchProject(projectId)
        notifyChanged()
        return list.size
    }

    // ------------------------------------------------------------------ exportação

    fun export(features: List<GeoFeature>, format: ExportFormat, name: String, prefs: CoordPrefs): ByteArray = when (format) {
        ExportFormat.KML -> Kml.writeKml(features, name)
        ExportFormat.KMZ -> Kml.writeKmz(features, name, { key, color -> runCatching { icons.png(key, color) }.getOrNull() }, { p -> media.photoBytes(p) })
        ExportFormat.GPX -> Gpx.write(features, name)
        ExportFormat.SHP -> Shapefile.writeZip(features, name, if (prefs.datum == DatumChoice.WGS84) Crs.PRJ_WGS84 else Crs.PRJ_SIRGAS2000)
        ExportFormat.GEOJSON -> GeoJsonIO.write(features, name)
        ExportFormat.CSV -> CsvIO.write(features, prefs)
        ExportFormat.DXF -> Dxf.write(features, prefs).bytes
        ExportFormat.GPKG -> {
            val tmp = File(context.cacheDir, "export_${System.currentTimeMillis()}.gpkg")
            try { Gpkg.write(tmp, features, name); tmp.readBytes() } finally { tmp.delete(); File(tmp.path + "-journal").delete() }
        }
    }
}
