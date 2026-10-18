package br.geomaps.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import br.geomaps.core.Codec
import br.geomaps.core.Coord
import br.geomaps.core.GeoFeature
import br.geomaps.core.Json
import br.geomaps.core.Project
import br.geomaps.core.jArr
import br.geomaps.core.jObj

/** Banco interno (SQLite). Geometrias, estilos e atributos são guardados em JSON compacto. */
class Db(context: Context) : SQLiteOpenHelper(context, "geomaps.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE projects(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, description TEXT DEFAULT '',
                visible INTEGER DEFAULT 1, form TEXT, created INTEGER, updated INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE features(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                kind TEXT NOT NULL, name TEXT, description TEXT, category TEXT, folder TEXT,
                geom TEXT NOT NULL, style TEXT, attrs TEXT, photos TEXT, ele_source TEXT, recorded INTEGER DEFAULT 0,
                min_lat REAL, min_lon REAL, max_lat REAL, max_lon REAL,
                created INTEGER, updated INTEGER)"""
        )
        db.execSQL("CREATE INDEX idx_features_project ON features(project_id)")
        db.execSQL(
            """CREATE TABLE versions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                label TEXT, created INTEGER, feature_count INTEGER, snapshot TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE offline_areas(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kind TEXT, source TEXT,
                min_lat REAL, min_lon REAL, max_lat REAL, max_lon REAL, ring TEXT,
                min_zoom INTEGER, max_zoom INTEGER, size INTEGER DEFAULT 0, tiles INTEGER DEFAULT 0,
                status TEXT, region_id INTEGER, created INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE custom_sources(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, type TEXT, url TEXT, layers TEXT,
                min_zoom INTEGER DEFAULT 0, max_zoom INTEGER DEFAULT 20, tile_size INTEGER DEFAULT 256,
                overlay INTEGER DEFAULT 0, opacity REAL DEFAULT 1, allow_offline INTEGER DEFAULT 0)"""
        )
        db.execSQL("CREATE TABLE rec_points(id INTEGER PRIMARY KEY AUTOINCREMENT, seg INTEGER, lat REAL, lon REAL, ele REAL, time INTEGER, acc REAL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------------ projetos

    fun projects(): List<Project> {
        val out = ArrayList<Project>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.description, p.visible, p.form, p.created, p.updated, (SELECT COUNT(*) FROM features f WHERE f.project_id = p.id) FROM projects p ORDER BY p.name COLLATE NOCASE",
            null,
        ).use { c ->
            while (c.moveToNext()) out += Project(
                id = c.getLong(0), name = c.getString(1), description = c.getString(2) ?: "",
                visible = c.getInt(3) == 1, form = Codec.formFromJson(c.getString(4)),
                created = c.getLong(5), updated = c.getLong(6), featureCount = c.getInt(7),
            )
        }
        return out
    }

    fun insertProject(p: Project): Long = writableDatabase.insert("projects", null, ContentValues().apply {
        put("name", p.name); put("description", p.description); put("visible", if (p.visible) 1 else 0)
        put("form", Codec.formToJson(p.form)); put("created", p.created); put("updated", System.currentTimeMillis())
    })

    fun updateProject(p: Project) {
        writableDatabase.update("projects", ContentValues().apply {
            put("name", p.name); put("description", p.description); put("visible", if (p.visible) 1 else 0)
            put("form", Codec.formToJson(p.form)); put("updated", System.currentTimeMillis())
        }, "id=?", arrayOf(p.id.toString()))
    }

    fun touchProject(id: Long) {
        writableDatabase.execSQL("UPDATE projects SET updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
    }

    fun deleteProject(id: Long) {
        writableDatabase.delete("projects", "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------ elementos

    private val featureCols = "id, project_id, kind, name, description, category, folder, geom, style, attrs, photos, ele_source, recorded, created, updated"

    private fun featureFrom(c: Cursor) = GeoFeature(
        id = c.getLong(0), projectId = c.getLong(1), name = c.getString(3) ?: "", description = c.getString(4) ?: "",
        category = c.getString(5) ?: "", folder = c.getString(6) ?: "", geom = Codec.geomFromJson(c.getString(7)),
        style = Codec.styleFromJson(c.getString(8)), attrs = Codec.attrsFromJson(c.getString(9)),
        photos = Codec.listFromJson(c.getString(10)), eleSource = c.getString(11), recorded = c.getInt(12) == 1,
        created = c.getLong(13), updated = c.getLong(14),
    )

    fun features(projectIds: Collection<Long>): List<GeoFeature> {
        if (projectIds.isEmpty()) return emptyList()
        val out = ArrayList<GeoFeature>()
        readableDatabase.rawQuery(
            "SELECT $featureCols FROM features WHERE project_id IN (${projectIds.joinToString(",")}) ORDER BY id", null,
        ).use { c -> while (c.moveToNext()) runCatching { out += featureFrom(c) } }
        return out
    }

    fun feature(id: Long): GeoFeature? =
        readableDatabase.rawQuery("SELECT $featureCols FROM features WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) featureFrom(c) else null
        }

    private fun values(f: GeoFeature) = ContentValues().apply {
        if (f.id > 0) put("id", f.id)
        put("project_id", f.projectId); put("kind", f.type.name); put("name", f.name); put("description", f.description)
        put("category", f.category); put("folder", f.folder); put("geom", Codec.geomToJson(f.geom))
        put("style", Codec.styleToJson(f.style)); put("attrs", Codec.attrsToJson(f.attrs)); put("photos", Codec.listToJson(f.photos))
        put("ele_source", f.eleSource); put("recorded", if (f.recorded) 1 else 0)
        val all = f.geom.allCoords()
        put("min_lat", all.minOfOrNull { it.lat }); put("min_lon", all.minOfOrNull { it.lon })
        put("max_lat", all.maxOfOrNull { it.lat }); put("max_lon", all.maxOfOrNull { it.lon })
        put("created", f.created); put("updated", System.currentTimeMillis())
    }

    fun insertFeature(f: GeoFeature): Long = writableDatabase.insert("features", null, values(f))

    fun insertFeatures(list: List<GeoFeature>): List<Long> {
        val db = writableDatabase
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            for (f in list) ids += db.insert("features", null, values(f))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    fun updateFeature(f: GeoFeature) {
        writableDatabase.update("features", values(f), "id=?", arrayOf(f.id.toString()))
    }

    fun deleteFeature(id: Long) {
        writableDatabase.delete("features", "id=?", arrayOf(id.toString()))
    }

    fun moveFeatures(fromProject: Long, toProject: Long) {
        writableDatabase.execSQL("UPDATE features SET project_id=? WHERE project_id=?", arrayOf<Any>(toProject, fromProject))
    }

    // ------------------------------------------------------------------ versões

    data class Version(val id: Long, val projectId: Long, val label: String, val created: Long, val count: Int)

    fun versions(projectId: Long): List<Version> {
        val out = ArrayList<Version>()
        readableDatabase.rawQuery("SELECT id, project_id, label, created, feature_count FROM versions WHERE project_id=? ORDER BY created DESC", arrayOf(projectId.toString())).use { c ->
            while (c.moveToNext()) out += Version(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getLong(3), c.getInt(4))
        }
        return out
    }

    fun insertVersion(projectId: Long, label: String, snapshot: String, count: Int) {
        writableDatabase.insert("versions", null, ContentValues().apply {
            put("project_id", projectId); put("label", label); put("created", System.currentTimeMillis())
            put("feature_count", count); put("snapshot", snapshot)
        })
        // mantém as 30 versões mais recentes
        writableDatabase.execSQL(
            "DELETE FROM versions WHERE project_id=? AND id NOT IN (SELECT id FROM versions WHERE project_id=? ORDER BY created DESC LIMIT 30)",
            arrayOf<Any>(projectId, projectId),
        )
    }

    fun versionSnapshot(id: Long): String? =
        readableDatabase.rawQuery("SELECT snapshot FROM versions WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun deleteVersion(id: Long) {
        writableDatabase.delete("versions", "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------ gravação em andamento

    fun recAdd(seg: Int, c: Coord) {
        writableDatabase.insert("rec_points", null, ContentValues().apply {
            put("seg", seg); put("lat", c.lat); put("lon", c.lon); put("ele", c.ele); put("time", c.time); put("acc", c.acc)
        })
    }

    fun recPoints(): List<Pair<Int, Coord>> {
        val out = ArrayList<Pair<Int, Coord>>()
        readableDatabase.rawQuery("SELECT seg, lat, lon, ele, time, acc FROM rec_points ORDER BY id", null).use { c ->
            while (c.moveToNext()) out += c.getInt(0) to Coord(
                c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3),
                if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getDouble(5),
            )
        }
        return out
    }

    fun recClear() {
        writableDatabase.delete("rec_points", null, null)
    }

    // ------------------------------------------------------------------ fontes personalizadas

    data class CustomSource(
        val id: Long = 0, val name: String, val type: String, val url: String, val layers: String = "",
        val minZoom: Int = 0, val maxZoom: Int = 20, val tileSize: Int = 256, val overlay: Boolean = false,
        val opacity: Float = 1f, val allowOffline: Boolean = false,
    )

    fun customSources(): List<CustomSource> {
        val out = ArrayList<CustomSource>()
        readableDatabase.rawQuery("SELECT id, name, type, url, layers, min_zoom, max_zoom, tile_size, overlay, opacity, allow_offline FROM custom_sources ORDER BY name", null).use { c ->
            while (c.moveToNext()) out += CustomSource(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: "",
                c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8) == 1, c.getFloat(9), c.getInt(10) == 1,
            )
        }
        return out
    }

    fun saveCustomSource(s: CustomSource): Long {
        val v = ContentValues().apply {
            put("name", s.name); put("type", s.type); put("url", s.url); put("layers", s.layers)
            put("min_zoom", s.minZoom); put("max_zoom", s.maxZoom); put("tile_size", s.tileSize)
            put("overlay", if (s.overlay) 1 else 0); put("opacity", s.opacity); put("allow_offline", if (s.allowOffline) 1 else 0)
        }
        return if (s.id == 0L) writableDatabase.insert("custom_sources", null, v)
        else { writableDatabase.update("custom_sources", v, "id=?", arrayOf(s.id.toString())); s.id }
    }

    fun deleteCustomSource(id: Long) {
        writableDatabase.delete("custom_sources", "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------------ áreas offline

    data class OfflineArea(
        val id: Long = 0, val name: String, val kind: String, val source: String,
        val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
        val ring: List<Coord>? = null, val minZoom: Int, val maxZoom: Int,
        val size: Long = 0, val tiles: Long = 0, val status: String = "pendente", val regionId: Long? = null,
        val created: Long = System.currentTimeMillis(),
    )

    private fun ringToJson(r: List<Coord>?) = r?.let { Json.write(it.map { c -> listOf(c.lon, c.lat) }) }
    private fun ringFromJson(s: String?): List<Coord>? = s?.let {
        Json.parse(it).jArr()?.mapNotNull { p -> p.jArr()?.let { a -> Coord((a[1] as Number).toDouble(), (a[0] as Number).toDouble()) } }
    }

    fun offlineAreas(): List<OfflineArea> {
        val out = ArrayList<OfflineArea>()
        readableDatabase.rawQuery(
            "SELECT id, name, kind, source, min_lat, min_lon, max_lat, max_lon, ring, min_zoom, max_zoom, size, tiles, status, region_id, created FROM offline_areas ORDER BY created DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) out += OfflineArea(
                c.getLong(0), c.getString(1) ?: "", c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5),
                c.getDouble(6), c.getDouble(7), ringFromJson(c.getString(8)), c.getInt(9), c.getInt(10), c.getLong(11),
                c.getLong(12), c.getString(13) ?: "", if (c.isNull(14)) null else c.getLong(14), c.getLong(15),
            )
        }
        return out
    }

    fun saveOfflineArea(a: OfflineArea): Long {
        val v = ContentValues().apply {
            put("name", a.name); put("kind", a.kind); put("source", a.source)
            put("min_lat", a.minLat); put("min_lon", a.minLon); put("max_lat", a.maxLat); put("max_lon", a.maxLon)
            put("ring", ringToJson(a.ring)); put("min_zoom", a.minZoom); put("max_zoom", a.maxZoom)
            put("size", a.size); put("tiles", a.tiles); put("status", a.status); put("region_id", a.regionId); put("created", a.created)
        }
        return if (a.id == 0L) writableDatabase.insert("offline_areas", null, v)
        else { writableDatabase.update("offline_areas", v, "id=?", arrayOf(a.id.toString())); a.id }
    }

    fun deleteOfflineArea(id: Long) {
        writableDatabase.delete("offline_areas", "id=?", arrayOf(id.toString()))
    }

    companion object {
        /** Lê o snapshot JSON de um projeto (lista de elementos). */
        fun parseSnapshot(s: String, projectId: Long): List<GeoFeature> =
            Json.parse(s).jObj()?.get("features").jArr()?.mapNotNull { it.jObj()?.let { m -> Codec.featureFromMap(m, projectId) } } ?: emptyList()
    }
}
