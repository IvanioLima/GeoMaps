package br.geomaps.core

/**
 * Interpreta arquivos .prj (WKT ESRI/OGC) suficientes para os casos comuns no Brasil:
 * geográficas e UTM/TM em SIRGAS 2000, WGS84, SAD69 e Córrego Alegre, além de Web Mercator.
 */
class Crs private constructor(
    val description: String,
    private val datum: Datum,
    private val tm: TransverseMercator?,
    private val webMercator: Boolean,
    private val unitToMeter: Double,
) {
    /** Converte (x, y) do arquivo para (lat, lon) SIRGAS 2000/WGS84. */
    fun toGeographic(x: Double, y: Double): Pair<Double, Double> {
        val (lat, lon) = when {
            webMercator -> WebMercator.inverse(x * unitToMeter, y * unitToMeter)
            tm != null -> tm.inverse(x * unitToMeter, y * unitToMeter)
            else -> y to x
        }
        return datum.toGeocentric(lat, lon)
    }

    companion object {
        val WGS84_GEO = Crs("Geográficas WGS84/SIRGAS", Datum.SIRGAS2000, null, false, 1.0)

        const val PRJ_SIRGAS2000 = """GEOGCS["GCS_SIRGAS_2000",DATUM["D_SIRGAS_2000",SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]"""
        const val PRJ_WGS84 = """GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]"""

        fun parse(wkt: String?): Crs {
            if (wkt.isNullOrBlank()) return WGS84_GEO
            val w = wkt.uppercase().replace(" ", "")
            val datum = when {
                w.contains("SAD_1969") || w.contains("SOUTH_AMERICAN_1969") || w.contains("SAD69") || w.contains("SAD_69") -> Datum.SAD69
                w.contains("CORREGO") || w.contains("CÓRREGO") -> Datum.CORREGO_ALEGRE
                else -> Datum.SIRGAS2000
            }
            val ell = Regex("""SPHEROID\["[^"]*",([\d.]+),([\d.]+)""").find(w)?.let {
                Ellipsoid("prj", it.groupValues[1].toDouble(), it.groupValues[2].toDouble().takeIf { f -> f > 0 } ?: 298.257222101)
            } ?: datum.ellipsoid
            val d = if (datum.isGeocentric) Datum(datum.name, ell) else datum.copy(ellipsoid = ell)
            if (!w.startsWith("PROJCS")) return Crs("Geográficas ${d.name}", d, null, false, 1.0)

            fun param(vararg names: String): Double? {
                for (n in names) {
                    Regex("""PARAMETER\["$n",(-?[\d.Ee+-]+)\]""").find(w)?.let { return it.groupValues[1].toDoubleOrNull() }
                }
                return null
            }
            val unit = Regex("""UNIT\["[^"]*",([\d.Ee+-]+)\]\]*$""").find(w)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""UNIT\["(?:METER|METRE|METERS)",([\d.]+)""").find(w)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val proj = Regex("""PROJECTION\["([^"]+)"""").find(w)?.groupValues?.get(1) ?: ""
            if (proj.contains("MERCATOR_AUXILIARY") || proj.contains("POPULAR_VISUALISATION") || w.contains("WEB_MERCATOR") || w.contains("PSEUDO-MERCATOR")) {
                return Crs("Web Mercator", d, null, true, unit)
            }
            if (!proj.contains("TRANSVERSE_MERCATOR")) throw GeoFormatException("Projeção não suportada no .prj: $proj")
            val tm = TransverseMercator(
                ell,
                lon0 = param("CENTRAL_MERIDIAN", "LONGITUDE_OF_CENTER", "LONGITUDE_OF_ORIGIN") ?: 0.0,
                k0 = param("SCALE_FACTOR") ?: 0.9996,
                falseEasting = (param("FALSE_EASTING") ?: 500000.0) * unit,
                falseNorthing = (param("FALSE_NORTHING") ?: 0.0) * unit,
                lat0 = param("LATITUDE_OF_ORIGIN", "LATITUDE_OF_CENTER") ?: 0.0,
            )
            val name = Regex("""PROJCS\["([^"]+)"""").find(wkt.replace(" ", ""))?.groupValues?.get(1) ?: "TM"
            return Crs(name, d, tm, false, unit)
        }
    }
}
