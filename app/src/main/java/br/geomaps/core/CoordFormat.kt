package br.geomaps.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

enum class CoordSystem(val label: String) {
    UTM("UTM"), DD("Graus decimais"), DMS("Graus, minutos e segundos")
}

enum class DatumChoice(val label: String) {
    SIRGAS2000("SIRGAS 2000"), WGS84("WGS 84");

    val ellipsoid get() = if (this == SIRGAS2000) Ellipsoid.GRS80 else Ellipsoid.WGS84
}

/** Preferências que afetam a formatação de coordenadas. */
data class CoordPrefs(
    val system: CoordSystem = CoordSystem.UTM,
    val datum: DatumChoice = DatumChoice.SIRGAS2000,
    /** Fuso UTM fixo (null = automático). */
    val fixedZone: Int? = null,
    val fixedSouth: Boolean = true,
)

object CoordFormat {
    private val L = Locale.US

    fun format(lat: Double, lon: Double, p: CoordPrefs): String = when (p.system) {
        CoordSystem.DD -> dd(lat, lon)
        CoordSystem.DMS -> dms(lat, lon)
        CoordSystem.UTM -> utm(lat, lon, p)
    }

    /** Formato em linhas separadas (painéis). */
    fun formatLines(lat: Double, lon: Double, p: CoordPrefs): List<String> = when (p.system) {
        CoordSystem.DD -> listOf(String.format(L, "%.6f°", lat), String.format(L, "%.6f°", lon))
        CoordSystem.DMS -> listOf(dmsOne(lat, true), dmsOne(lon, false))
        CoordSystem.UTM -> {
            val u = toUtm(lat, lon, p)
            listOf("${u.zone}${u.hemisphere}", String.format(L, "E %.2f", u.e), String.format(L, "N %.2f", u.n))
        }
    }

    fun toUtm(lat: Double, lon: Double, p: CoordPrefs): UtmCoord =
        Utm.fromLatLon(lat, lon, p.fixedZone, if (p.fixedZone != null) p.fixedSouth else null, p.datum.ellipsoid)

    fun dd(lat: Double, lon: Double) = String.format(L, "%.6f, %.6f", lat, lon)

    fun utm(lat: Double, lon: Double, p: CoordPrefs): String {
        val u = toUtm(lat, lon, p)
        return String.format(L, "%d%s E %.2f N %.2f", u.zone, u.hemisphere, u.e, u.n)
    }

    fun dms(lat: Double, lon: Double) = dmsOne(lat, true) + " " + dmsOne(lon, false)

    fun dmsOne(v: Double, isLat: Boolean): String {
        val (d, m, s) = toDms(v)
        val h = if (isLat) (if (v < 0) "S" else "N") else (if (v < 0) "W" else "E")
        return String.format(L, if (isLat) "%02d°%02d'%05.2f\"%s" else "%03d°%02d'%05.2f\"%s", d, m, s, h)
    }

    fun toDms(v: Double): Triple<Int, Int, Double> {
        val a = abs(v)
        var d = floor(a).toInt()
        var m = floor((a - d) * 60).toInt()
        var s = ((a - d) * 60 - m) * 60
        if (s >= 59.995) { s = 0.0; m += 1 }
        if (m >= 60) { m = 0; d += 1 }
        return Triple(d, m, s)
    }

    fun fromDms(d: Double, m: Double, s: Double, negative: Boolean): Double {
        val v = abs(d) + m / 60 + s / 3600
        return if (negative) -v else v
    }

    /** Converte texto numérico aceitando vírgula decimal. */
    fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    /**
     * Interpreta texto livre de coordenada:
     *  "-5.12, -36.5" · "5°07'24\"S 36°30'00\"W" · "24S 435850 9612306" · "24 S E435850 N9612306"
     */
    fun parse(text: String, p: CoordPrefs = CoordPrefs()): Pair<Double, Double>? {
        val t = text.trim().uppercase(L)
        if (t.isEmpty()) return null
        // UTM: fuso + hemisfério + E + N
        Regex("""^(\d{1,2})\s*([NS])[\s,;]+E?\s*([\d.,]+)[\s,;]+N?\s*([\d.,]+)$""").find(t)?.let { mt ->
            val z = mt.groupValues[1].toInt()
            val south = mt.groupValues[2] == "S"
            val e = mt.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
            val n = mt.groupValues[4].replace(',', '.').toDoubleOrNull() ?: return null
            if (z !in 1..60) return null
            return Utm.toLatLon(UtmCoord(z, south, e, n), p.datum.ellipsoid)
        }
        // GMS
        if (t.contains('°') || t.contains('\'') || t.contains('"')) {
            val parts = Regex("""(-?\d+(?:[.,]\d+)?)\s*°\s*(?:(\d+(?:[.,]\d+)?)\s*['′]\s*)?(?:(\d+(?:[.,]\d+)?)\s*(?:"|″|'')\s*)?([NSEWLO])?""")
                .findAll(t).toList()
            if (parts.size >= 2) {
                fun conv(mt: MatchResult): Pair<Double, Char?> {
                    val d = mt.groupValues[1].replace(',', '.').toDouble()
                    val m = mt.groupValues[2].replace(',', '.').toDoubleOrNull() ?: 0.0
                    val s = mt.groupValues[3].replace(',', '.').toDoubleOrNull() ?: 0.0
                    val h = mt.groupValues[4].firstOrNull()
                    val neg = d < 0 || h == 'S' || h == 'W' || h == 'O'
                    return fromDms(d, m, s, neg) to h
                }
                val (a, ha) = conv(parts[0])
                val (b, _) = conv(parts[1])
                return if (ha == 'E' || ha == 'W' || ha == 'L' || ha == 'O') b to a else a to b
            }
        }
        // Decimal
        val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(t.replace(Regex("""(\d),(\d)"""), "$1.$2")).map { it.value.toDouble() }.toList()
        if (nums.size == 2) {
            val (a, b) = nums
            if (abs(a) <= 90 && abs(b) <= 180) return a to b
        }
        return null
    }

    fun distance(m: Double, useKm: Boolean): String = when {
        useKm && m >= 1000 -> String.format(L, "%.2f km", m / 1000)
        useKm -> String.format(L, "%.0f m", m)
        m >= 100 -> String.format(L, "%.0f m", m)
        else -> String.format(L, "%.1f m", m)
    }

    fun area(m2: Double, useKm: Boolean): String = when {
        useKm && m2 >= 1_000_000 -> String.format(L, "%.3f km²", m2 / 1e6)
        m2 >= 10_000 -> String.format(L, "%.2f m² (%.4f ha)", m2, m2 / 10000)
        else -> String.format(L, "%.2f m²", m2)
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(L, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}
