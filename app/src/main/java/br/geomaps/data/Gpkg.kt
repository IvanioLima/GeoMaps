package br.geomaps.data

import android.database.sqlite.SQLiteDatabase
import br.geomaps.core.Codec
import br.geomaps.core.FeatureStyle
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeoFormatException
import br.geomaps.core.GeoMath
import br.geomaps.core.GeomType
import br.geomaps.core.ImportResult
import br.geomaps.core.Wkb
import java.io.File

/** Leitura e escrita de GeoPackage (OGC) usando o SQLite do Android. */
object Gpkg {

    fun write(file: File, features: List<GeoFeature>, projectName: String) {
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA application_id = 1196444487")
            db.execSQL("PRAGMA user_version = 10300")
            db.execSQL("CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL, organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)")
            db.execSQL("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined cartesian SRS', -1, 'NONE', -1, 'undefined', NULL)")
            db.execSQL("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined geographic SRS', 0, 'NONE', 0, 'undefined', NULL)")
            db.execSQL(
                "INSERT INTO gpkg_spatial_ref_sys VALUES ('WGS 84 geodetic', 4326, 'EPSG', 4326, ?, 'longitude/latitude WGS84')",
                arrayOf<Any>("""GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]"""),
            )
            db.execSQL("CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER)")
            db.execSQL("CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name))")

            val keys = LinkedHashSet<String>()
            features.forEach { keys.addAll(it.attrs.keys) }
            val reserved = setOf("fid", "geom", "nome", "descricao", "categoria", "pasta", "estilo")
            val attrCols = keys.filter { it.lowercase() !in reserved }
            for ((t, table, gtype) in listOf(
                Triple(GeomType.POINT, "pontos", "POINT"), Triple(GeomType.LINE, "trilhas", "GEOMETRY"), Triple(GeomType.POLYGON, "areas", "GEOMETRY"),
            )) {
                val fs = features.filter { it.type == t }
                if (fs.isEmpty()) continue
                val cols = attrCols.joinToString("") { ", \"" + it.replace("\"", "") + "\" TEXT" }
                db.execSQL("CREATE TABLE $table (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom BLOB, nome TEXT, descricao TEXT, categoria TEXT, pasta TEXT, estilo TEXT$cols)")
                val b = GeoMath.bounds(fs.flatMap { it.geom.allCoords() })!!
                db.execSQL(
                    "INSERT INTO gpkg_contents (table_name, data_type, identifier, description, min_x, min_y, max_x, max_y, srs_id) VALUES (?, 'features', ?, ?, ?, ?, ?, ?, 4326)",
                    arrayOf<Any>(table, "$projectName - $table", projectName, b.minLon, b.minLat, b.maxLon, b.maxLat),
                )
                val hasZ = fs.any { f -> f.geom.allCoords().any { it.ele != null } }
                db.execSQL("INSERT INTO gpkg_geometry_columns VALUES (?, 'geom', ?, 4326, ?, 0)", arrayOf<Any>(table, gtype, if (hasZ) 2 else 0))
                db.beginTransaction()
                try {
                    val placeholders = (attrCols.size + 6)
                    val sql = "INSERT INTO $table (geom, nome, descricao, categoria, pasta, estilo" +
                        attrCols.joinToString("") { ", \"" + it.replace("\"", "") + "\"" } + ") VALUES (" + List(placeholders) { "?" }.joinToString(",") + ")"
                    val st = db.compileStatement(sql)
                    for (f in fs) {
                        st.clearBindings()
                        st.bindBlob(1, Wkb.gpkgBlob(f.geom))
                        st.bindString(2, f.name); st.bindString(3, f.description); st.bindString(4, f.category)
                        st.bindString(5, f.folder); st.bindString(6, Codec.styleToJson(f.style))
                        attrCols.forEachIndexed { i, k -> f.attrs[k]?.let { st.bindString(7 + i, it) } ?: st.bindNull(7 + i) }
                        st.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            db.close()
        }
    }

    fun read(file: File, sourceName: String): ImportResult {
        val db = runCatching { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
            .getOrElse { throw GeoFormatException("GeoPackage inválido: ${it.message}") }
        val out = ArrayList<GeoFeature>()
        val warnings = ArrayList<String>()
        try {
            val tables = ArrayList<Pair<String, String>>()
            db.rawQuery("SELECT table_name, column_name FROM gpkg_geometry_columns", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0) to c.getString(1)
            }
            if (tables.isEmpty()) throw GeoFormatException("GeoPackage sem tabelas vetoriais")
            for ((table, gcol) in tables) {
                val srs = db.rawQuery("SELECT srs_id FROM gpkg_geometry_columns WHERE table_name=?", arrayOf(table)).use { c -> if (c.moveToFirst()) c.getInt(0) else 4326 }
                if (srs != 4326 && srs != 4674 && srs != 4258 && srs != 0) warnings += "$table: SRS $srs não é geográfico; as coordenadas podem estar incorretas"
                db.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
                    val names = c.columnNames
                    val gi = names.indexOfFirst { it.equals(gcol, true) }
                    while (c.moveToNext()) {
                        val blob = if (gi >= 0 && !c.isNull(gi)) c.getBlob(gi) else continue
                        val geoms = runCatching { Wkb.fromGpkgBlob(blob) }.getOrElse { warnings += "$table: geometria ilegível"; continue }
                        val attrs = LinkedHashMap<String, String>()
                        var name = ""; var desc = ""; var cat = ""; var folder = table; var style: FeatureStyle? = null
                        for (i in names.indices) {
                            if (i == gi) continue
                            val n = names[i]
                            val v = when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> continue
                                android.database.Cursor.FIELD_TYPE_BLOB -> continue
                                else -> c.getString(i) ?: continue
                            }
                            when (n.lowercase()) {
                                "fid" -> {}
                                "nome", "name" -> name = v
                                "descricao", "description" -> desc = v
                                "categoria", "category" -> cat = v
                                "pasta" -> folder = v
                                "estilo" -> style = Codec.styleFromJson(v)
                                else -> attrs[n] = v
                            }
                        }
                        for (g in geoms) out += GeoFeature(
                            name = name.ifEmpty { "$table ${out.size + 1}" }, description = desc, category = cat, folder = folder,
                            geom = g, attrs = attrs, style = style ?: FeatureStyle(),
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
        if (out.isEmpty()) throw GeoFormatException("Nenhum elemento no GeoPackage")
        return ImportResult(sourceName, out, emptyMap(), warnings)
    }
}
