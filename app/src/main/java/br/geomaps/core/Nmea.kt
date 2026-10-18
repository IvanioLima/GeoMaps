package br.geomaps.core

import java.util.Calendar
import java.util.TimeZone

/** Estado de posição consolidado a partir de sentenças NMEA 0183 (GNSS externo / receptor interno). */
data class NmeaFix(
    val lat: Double,
    val lon: Double,
    /** Altitude ortométrica (MSL) informada pelo receptor. */
    val altMsl: Double?,
    /** Separação geoidal N (altura elipsoidal = MSL + N). */
    val geoidSep: Double?,
    val quality: Int,
    val satellites: Int,
    val hdop: Double?,
    val speedMs: Double?,
    val course: Double?,
    val accuracy: Double?,
    val time: Long,
) {
    val qualityLabel: String
        get() = when (quality) {
            0 -> "Sem solução"; 1 -> "Autônomo"; 2 -> "DGPS/SBAS"; 3 -> "PPS"
            4 -> "RTK fixo"; 5 -> "RTK flutuante"; 6 -> "Estimado"; 7 -> "Manual"; 8 -> "Simulação"
            else -> "Q$quality"
        }
}

class NmeaParser {
    private var lat: Double? = null
    private var lon: Double? = null
    private var alt: Double? = null
    private var sep: Double? = null
    private var quality = 0
    private var sats = 0
    private var hdop: Double? = null
    private var speed: Double? = null
    private var course: Double? = null
    private var stdLat: Double? = null
    private var stdLon: Double? = null
    private var dateMs: Long? = null
    private var timeOfDayMs: Long? = null

    companion object {
        fun checksumOk(s: String): Boolean {
            val star = s.lastIndexOf('*')
            if (!s.startsWith("$") || star < 0) return !s.contains('*')
            var x = 0
            for (i in 1 until star) x = x xor s[i].code
            val given = s.substring(star + 1).trim().take(2).toIntOrNull(16) ?: return false
            return x == given
        }

        fun coord(v: String, h: String): Double? {
            if (v.isEmpty()) return null
            val dot = v.indexOf('.').let { if (it < 0) v.length else it }
            if (dot < 3) return null
            val deg = v.substring(0, dot - 2).toDoubleOrNull() ?: return null
            val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
            val d = deg + min / 60
            return if (h == "S" || h == "W") -d else d
        }

        private fun hms(t: String): Long? {
            if (t.length < 6) return null
            val h = t.substring(0, 2).toIntOrNull() ?: return null
            val m = t.substring(2, 4).toIntOrNull() ?: return null
            val s = t.substring(4).toDoubleOrNull() ?: return null
            return ((h * 3600 + m * 60) * 1000L) + (s * 1000).toLong()
        }
    }

    /**
     * Processa uma sentença. Retorna um fix quando uma sentença com posição (GGA ou RMC) é concluída.
     */
    fun feed(line: String): NmeaFix? {
        val s = line.trim()
        if (!s.startsWith("$") || s.length < 7 || !checksumOk(s)) return null
        val body = s.substring(1, s.lastIndexOf('*').let { if (it < 0) s.length else it })
        val f = body.split(',')
        val type = f[0].takeLast(3)
        when (type) {
            "GGA" -> {
                timeOfDayMs = hms(f.getOrElse(1) { "" }) ?: timeOfDayMs
                lat = coord(f.getOrElse(2) { "" }, f.getOrElse(3) { "" })
                lon = coord(f.getOrElse(4) { "" }, f.getOrElse(5) { "" })
                quality = f.getOrElse(6) { "0" }.toIntOrNull() ?: 0
                sats = f.getOrElse(7) { "0" }.toIntOrNull() ?: 0
                hdop = f.getOrNull(8)?.toDoubleOrNull()
                alt = f.getOrNull(9)?.toDoubleOrNull()
                sep = f.getOrNull(11)?.toDoubleOrNull()
                return build()
            }
            "RMC" -> {
                timeOfDayMs = hms(f.getOrElse(1) { "" }) ?: timeOfDayMs
                if (f.getOrNull(2) != "A") return null
                f.getOrNull(9)?.takeIf { it.length == 6 }?.let { d ->
                    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    c.clear()
                    c.set(2000 + d.substring(4, 6).toInt(), d.substring(2, 4).toInt() - 1, d.substring(0, 2).toInt())
                    dateMs = c.timeInMillis
                }
                speed = f.getOrNull(7)?.toDoubleOrNull()?.let { it * 0.514444 }
                course = f.getOrNull(8)?.toDoubleOrNull()
                if (lat == null) {
                    lat = coord(f.getOrElse(3) { "" }, f.getOrElse(4) { "" })
                    lon = coord(f.getOrElse(5) { "" }, f.getOrElse(6) { "" })
                    if (quality == 0) quality = 1
                    return build()
                }
            }
            "VTG" -> {
                course = f.getOrNull(1)?.toDoubleOrNull() ?: course
                speed = f.getOrNull(7)?.toDoubleOrNull()?.let { it / 3.6 } ?: speed
            }
            "GST" -> {
                stdLat = f.getOrNull(6)?.toDoubleOrNull()
                stdLon = f.getOrNull(7)?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun build(): NmeaFix? {
        val la = lat ?: return null
        val lo = lon ?: return null
        if (quality == 0) return null
        val acc = if (stdLat != null && stdLon != null) Math.sqrt(stdLat!! * stdLat!! + stdLon!! * stdLon!!)
        else hdop?.let { it * 3.0 }
        val t = if (dateMs != null && timeOfDayMs != null) dateMs!! + timeOfDayMs!! else System.currentTimeMillis()
        return NmeaFix(la, lo, alt, sep, quality, sats, hdop, speed, course, acc, t)
    }
}
