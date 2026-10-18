package br.geomaps.core

import kotlin.math.*

data class Ellipsoid(val name: String, val a: Double, val invF: Double) {
    val f get() = 1.0 / invF
    val e2 get() = f * (2 - f)

    companion object {
        val GRS80 = Ellipsoid("GRS 1980", 6378137.0, 298.257222101)
        val WGS84 = Ellipsoid("WGS 84", 6378137.0, 298.257223563)
        val GRS67 = Ellipsoid("GRS 1967 (SAD69)", 6378160.0, 298.25)
        val INTL1924 = Ellipsoid("Internacional 1924", 6378388.0, 297.0)
    }
}

/** Datum geodésico com parâmetros de translação (3 parâmetros) para SIRGAS 2000/WGS84. */
data class Datum(val name: String, val ellipsoid: Ellipsoid, val dx: Double = 0.0, val dy: Double = 0.0, val dz: Double = 0.0) {
    val isGeocentric get() = dx == 0.0 && dy == 0.0 && dz == 0.0

    companion object {
        val SIRGAS2000 = Datum("SIRGAS 2000", Ellipsoid.GRS80)
        val WGS84 = Datum("WGS 84", Ellipsoid.WGS84)
        /** Parâmetros oficiais IBGE SAD69 → SIRGAS 2000. */
        val SAD69 = Datum("SAD 69", Ellipsoid.GRS67, -67.35, 3.88, -38.22)
        /** Parâmetros oficiais IBGE Córrego Alegre → SIRGAS 2000. */
        val CORREGO_ALEGRE = Datum("Córrego Alegre", Ellipsoid.INTL1924, -206.05, 168.28, -3.82)
    }

    /** Converte coordenadas deste datum para SIRGAS 2000 (≈ WGS84). */
    fun toGeocentric(lat: Double, lon: Double): Pair<Double, Double> {
        if (isGeocentric) return lat to lon
        val (x, y, z) = Geodesy.toEcef(lat, lon, 0.0, ellipsoid)
        val r = Geodesy.fromEcef(x + dx, y + dy, z + dz, Ellipsoid.GRS80)
        return r.first to r.second
    }

    fun fromGeocentric(lat: Double, lon: Double): Pair<Double, Double> {
        if (isGeocentric) return lat to lon
        val (x, y, z) = Geodesy.toEcef(lat, lon, 0.0, Ellipsoid.GRS80)
        val r = Geodesy.fromEcef(x - dx, y - dy, z - dz, ellipsoid)
        return r.first to r.second
    }
}

object Geodesy {
    fun toEcef(latDeg: Double, lonDeg: Double, h: Double, e: Ellipsoid): Triple<Double, Double, Double> {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
        val n = e.a / sqrt(1 - e.e2 * sin(lat) * sin(lat))
        return Triple((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1 - e.e2) + h) * sin(lat))
    }

    fun fromEcef(x: Double, y: Double, z: Double, e: Ellipsoid): Triple<Double, Double, Double> {
        val p = sqrt(x * x + y * y)
        val lon = atan2(y, x)
        var lat = atan2(z, p * (1 - e.e2))
        var h = 0.0
        repeat(8) {
            val n = e.a / sqrt(1 - e.e2 * sin(lat) * sin(lat))
            h = p / cos(lat) - n
            lat = atan2(z, p * (1 - e.e2 * n / (n + h)))
        }
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }
}

/**
 * Projeção Transversa de Mercator pelas séries de Krüger (6ª ordem, precisão sub-milimétrica
 * dentro do fuso). Serve para UTM e para qualquer TM lida de arquivos .prj.
 */
class TransverseMercator(
    val ellipsoid: Ellipsoid,
    val lon0: Double,
    val k0: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
    val lat0: Double = 0.0,
) {
    private val n: Double
    private val A: Double
    private val alpha: DoubleArray
    private val beta: DoubleArray
    private val e: Double
    private val northingOffset: Double

    init {
        val f = ellipsoid.f
        n = f / (2 - f)
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        A = ellipsoid.a / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
        alpha = doubleArrayOf(
            0.0,
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800,
            13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360,
            61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440,
            49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600,
            34729 * n5 / 80640 - 3418889 * n6 / 1995840,
            212378941 * n6 / 319334400,
        )
        beta = doubleArrayOf(
            0.0,
            n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800,
            n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720,
            17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720,
            4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600,
            4583 * n5 / 161280 - 108847 * n6 / 3991680,
            20648693 * n6 / 638668800,
        )
        e = sqrt(ellipsoid.e2)
        northingOffset = if (lat0 == 0.0) 0.0 else rawForward(lat0, lon0).second
    }

    private fun rawForward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg - lon0)
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xiP = atan2(t, cos(lam))
        val etaP = atanh(sin(lam) / sqrt(1 + t * t))
        var xi = xiP; var eta = etaP
        for (j in 1..6) {
            xi += alpha[j] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return (k0 * A * eta) to (k0 * A * xi)
    }

    /** (lat, lon) → (E, N) */
    fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val (x, y) = rawForward(latDeg, lonDeg)
        return (x + falseEasting) to (y - northingOffset + falseNorthing)
    }

    /** (E, N) → (lat, lon) */
    fun inverse(easting: Double, northing: Double): Pair<Double, Double> {
        val eta = (easting - falseEasting) / (k0 * A)
        val xi = (northing - falseNorthing + northingOffset) / (k0 * A)
        var xiP = xi; var etaP = eta
        for (j in 1..6) {
            xiP -= beta[j] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val sinhEtaP = sinh(etaP); val sinXiP = sin(xiP); val cosXiP = cos(xiP)
        val tauP = sinXiP / sqrt(sinhEtaP * sinhEtaP + cosXiP * cosXiP)
        var tau = tauP
        repeat(10) {
            val sigma = sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
            val tauPi = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
            val dTau = (tauP - tauPi) / sqrt(1 + tauPi * tauPi) *
                (1 + (1 - ellipsoid.e2) * tau * tau) / ((1 - ellipsoid.e2) * sqrt(1 + tau * tau))
            tau += dTau
            if (abs(dTau) < 1e-12) return@repeat
        }
        val phi = atan(tau)
        val lam = atan2(sinhEtaP, cosXiP)
        return Math.toDegrees(phi) to (lon0 + Math.toDegrees(lam))
    }
}

data class UtmCoord(val zone: Int, val south: Boolean, val e: Double, val n: Double) {
    val hemisphere get() = if (south) "S" else "N"
    override fun toString() = "%d%s %.2f %.2f".format(zone, hemisphere, e, n)
}

object Utm {
    fun zoneFor(lat: Double, lon: Double): Int {
        var z = floor((lon + 180) / 6).toInt() + 1
        if (z > 60) z = 60
        if (z < 1) z = 1
        // Exceções da Noruega/Svalbard
        if (lat in 56.0..64.0 && lon in 3.0..12.0) z = 32
        if (lat >= 72.0 && lat < 84.0) {
            if (lon >= 0 && lon < 9) z = 31 else if (lon >= 9 && lon < 21) z = 33
            else if (lon >= 21 && lon < 33) z = 35 else if (lon >= 33 && lon < 42) z = 37
        }
        return z
    }

    fun centralMeridian(zone: Int) = zone * 6.0 - 183.0

    fun tm(zone: Int, south: Boolean, e: Ellipsoid = Ellipsoid.GRS80) =
        TransverseMercator(e, centralMeridian(zone), 0.9996, 500000.0, if (south) 10000000.0 else 0.0)

    /** Converte geográficas para UTM. [forceZone]/[forceSouth] fixam o fuso (útil num levantamento). */
    fun fromLatLon(lat: Double, lon: Double, forceZone: Int? = null, forceSouth: Boolean? = null, e: Ellipsoid = Ellipsoid.GRS80): UtmCoord {
        val zone = forceZone ?: zoneFor(lat, lon)
        val south = forceSouth ?: (lat < 0)
        val (x, y) = tm(zone, south, e).forward(lat, lon)
        return UtmCoord(zone, south, x, y)
    }

    fun toLatLon(u: UtmCoord, e: Ellipsoid = Ellipsoid.GRS80): Pair<Double, Double> = tm(u.zone, u.south, e).inverse(u.e, u.n)

    /** Código EPSG SIRGAS 2000 / UTM (fusos 17–25 S e 17–22 N usados no Brasil). */
    fun sirgasEpsg(zone: Int, south: Boolean): Int? = when {
        south && zone in 17..25 -> 31960 + zone
        !south && zone in 17..22 -> 31954 + zone
        else -> null
    }

    fun wgsEpsg(zone: Int, south: Boolean) = (if (south) 32700 else 32600) + zone
}

/** Web Mercator (EPSG:3857), usado em WMS e no cálculo de tiles. */
object WebMercator {
    const val R = 6378137.0
    fun forward(lat: Double, lon: Double): Pair<Double, Double> {
        val l = lat.coerceIn(-85.05112878, 85.05112878)
        return (R * Math.toRadians(lon)) to (R * ln(tan(PI / 4 + Math.toRadians(l) / 2)))
    }

    fun inverse(x: Double, y: Double): Pair<Double, Double> =
        Math.toDegrees(2 * atan(exp(y / R)) - PI / 2) to Math.toDegrees(x / R)
}
