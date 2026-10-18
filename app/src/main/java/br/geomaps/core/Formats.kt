package br.geomaps.core

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    KMZ("KMZ (Google Earth, com ícones e fotos)", "kmz", "application/vnd.google-earth.kmz"),
    KML("KML", "kml", "application/vnd.google-earth.kml+xml"),
    GPX("GPX", "gpx", "application/gpx+xml"),
    SHP("Shapefile (.zip)", "zip", "application/zip"),
    GEOJSON("GeoJSON", "geojson", "application/geo+json"),
    GPKG("GeoPackage", "gpkg", "application/geopackage+sqlite3"),
    CSV("CSV (pontos)", "csv", "text/csv"),
    DXF("DXF (CAD, em UTM)", "dxf", "application/dxf"),
}

object Formats {
    /** Identifica o formato pelo nome e pelo conteúdo. */
    fun detect(name: String, bytes: ByteArray): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val isSqlite = bytes.size > 16 && String(bytes, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
        return when {
            isSqlite -> "gpkg"
            ext == "kmz" -> "kmz"
            ext == "zip" || ext == "shz" -> if (isZip && zipHasKml(bytes)) "kmz" else "shpzip"
            ext == "shp" -> "shp"
            ext in listOf("kml", "gpx", "csv", "txt") -> ext
            ext in listOf("geojson", "json") -> "geojson"
            isZip -> if (zipHasKml(bytes)) "kmz" else "shpzip"
            else -> {
                val head = String(bytes, 0, minOf(bytes.size, 600), Charsets.UTF_8)
                when {
                    head.contains("<gpx", true) -> "gpx"
                    head.contains("<kml", true) -> "kml"
                    head.trimStart().startsWith("{") -> "geojson"
                    else -> "csv"
                }
            }
        }
    }

    private fun zipHasKml(bytes: ByteArray): Boolean = runCatching {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            var hasKml = false; var hasShp = false
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".kml", true)) hasKml = true
                if (e.name.endsWith(".shp", true)) hasShp = true
            }
            hasKml && !hasShp
        }
    }.getOrDefault(false)

    /** Lê formatos que não dependem do Android (GeoPackage é tratado na camada de dados). */
    fun read(name: String, bytes: ByteArray, prefs: CoordPrefs): ImportResult = when (detect(name, bytes)) {
        "kmz" -> Kml.readKmz(bytes, name)
        "kml" -> Kml.read(bytes, name)
        "gpx" -> Gpx.read(bytes, name)
        "shpzip" -> Shapefile.readZip(bytes, name)
        "shp" -> throw GeoFormatException("Para importar shapefile, envie um .zip contendo .shp, .shx, .dbf e .prj")
        "geojson" -> GeoJsonIO.read(bytes, name)
        "csv", "txt" -> CsvIO.read(bytes, name, prefs)
        else -> throw GeoFormatException("Formato não reconhecido: $name")
    }
}
