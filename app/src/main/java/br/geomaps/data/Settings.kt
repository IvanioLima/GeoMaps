package br.geomaps.data

import android.content.Context
import android.content.SharedPreferences
import br.geomaps.core.CoordPrefs
import br.geomaps.core.CoordSystem
import br.geomaps.core.DatumChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GpsProfile(val label: String, val intervalMs: Long, val distanceM: Float) {
    HIGH("Alta precisão (1 s)", 1000, 0f),
    BALANCED("Equilibrado (5 s / 5 m)", 5000, 5f),
    SAVER("Economia (15 s / 20 m)", 15000, 20f),
    CUSTOM("Personalizado", 0, 0f),
}

data class Settings(
    val gpsProfile: GpsProfile = GpsProfile.HIGH,
    val customIntervalSec: Int = 2,
    val customDistanceM: Int = 2,
    val useKm: Boolean = true,
    val coordSystem: CoordSystem = CoordSystem.UTM,
    val datum: DatumChoice = DatumChoice.SIRGAS2000,
    val fixedZone: Int? = null,
    val fixedSouth: Boolean = true,
    /** Fonte padrão da altitude de trilhas gravadas: "gps" ou "dem". */
    val eleSourceDefault: String = "gps",
    /** "copernicus" ou "srtm" */
    val demSource: String = "copernicus",
    val cacheLimitMb: Int = 2048,
    val snapping: Boolean = true,
    val snapPx: Int = 24,
    val googleKey: String = "",
    val arcgisKey: String = "",
    val baseMap: String = "osm",
    val overlays: Set<String> = emptySet(),
    val labels: Boolean = true,
    /** "internal" ou "bt" */
    val gnssSource: String = "internal",
    val btAddress: String = "",
    val btName: String = "",
    val deviationAlertM: Int = 30,
    val recMaxAccuracyM: Int = 50,
    val autoBackup: Boolean = false,
    val activeProjectId: Long = 0,
    val keepScreenOn: Boolean = true,
    val telemetry: Boolean = false,
) {
    val coordPrefs get() = CoordPrefs(coordSystem, datum, fixedZone, fixedSouth)
    val intervalMs get() = if (gpsProfile == GpsProfile.CUSTOM) customIntervalSec * 1000L else gpsProfile.intervalMs
    val distanceM get() = if (gpsProfile == GpsProfile.CUSTOM) customDistanceM.toFloat() else gpsProfile.distanceM
}

class SettingsStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("geomaps_settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow
    val value get() = _flow.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            gpsProfile = runCatching { GpsProfile.valueOf(sp.getString("gpsProfile", d.gpsProfile.name)!!) }.getOrDefault(d.gpsProfile),
            customIntervalSec = sp.getInt("customIntervalSec", d.customIntervalSec),
            customDistanceM = sp.getInt("customDistanceM", d.customDistanceM),
            useKm = sp.getBoolean("useKm", d.useKm),
            coordSystem = runCatching { CoordSystem.valueOf(sp.getString("coordSystem", d.coordSystem.name)!!) }.getOrDefault(d.coordSystem),
            datum = runCatching { DatumChoice.valueOf(sp.getString("datum", d.datum.name)!!) }.getOrDefault(d.datum),
            fixedZone = sp.getInt("fixedZone", 0).takeIf { it in 1..60 },
            fixedSouth = sp.getBoolean("fixedSouth", d.fixedSouth),
            eleSourceDefault = sp.getString("eleSourceDefault", d.eleSourceDefault)!!,
            demSource = sp.getString("demSource", d.demSource)!!,
            cacheLimitMb = sp.getInt("cacheLimitMb", d.cacheLimitMb),
            snapping = sp.getBoolean("snapping", d.snapping),
            snapPx = sp.getInt("snapPx", d.snapPx),
            googleKey = sp.getString("googleKey", "")!!,
            arcgisKey = sp.getString("arcgisKey", "")!!,
            baseMap = sp.getString("baseMap", d.baseMap)!!,
            overlays = sp.getStringSet("overlays", emptySet())!!.toSet(),
            labels = sp.getBoolean("labels", d.labels),
            gnssSource = sp.getString("gnssSource", d.gnssSource)!!,
            btAddress = sp.getString("btAddress", "")!!,
            btName = sp.getString("btName", "")!!,
            deviationAlertM = sp.getInt("deviationAlertM", d.deviationAlertM),
            recMaxAccuracyM = sp.getInt("recMaxAccuracyM", d.recMaxAccuracyM),
            autoBackup = sp.getBoolean("autoBackup", d.autoBackup),
            activeProjectId = sp.getLong("activeProjectId", 0),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            telemetry = sp.getBoolean("telemetry", d.telemetry),
        )
    }

    fun update(f: (Settings) -> Settings) {
        val s = f(_flow.value)
        sp.edit()
            .putString("gpsProfile", s.gpsProfile.name)
            .putInt("customIntervalSec", s.customIntervalSec)
            .putInt("customDistanceM", s.customDistanceM)
            .putBoolean("useKm", s.useKm)
            .putString("coordSystem", s.coordSystem.name)
            .putString("datum", s.datum.name)
            .putInt("fixedZone", s.fixedZone ?: 0)
            .putBoolean("fixedSouth", s.fixedSouth)
            .putString("eleSourceDefault", s.eleSourceDefault)
            .putString("demSource", s.demSource)
            .putInt("cacheLimitMb", s.cacheLimitMb)
            .putBoolean("snapping", s.snapping)
            .putInt("snapPx", s.snapPx)
            .putString("googleKey", s.googleKey.trim())
            .putString("arcgisKey", s.arcgisKey.trim())
            .putString("baseMap", s.baseMap)
            .putStringSet("overlays", s.overlays)
            .putBoolean("labels", s.labels)
            .putString("gnssSource", s.gnssSource)
            .putString("btAddress", s.btAddress)
            .putString("btName", s.btName)
            .putInt("deviationAlertM", s.deviationAlertM)
            .putInt("recMaxAccuracyM", s.recMaxAccuracyM)
            .putBoolean("autoBackup", s.autoBackup)
            .putLong("activeProjectId", s.activeProjectId)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("telemetry", s.telemetry)
            .apply()
        _flow.value = s
    }

    /** Armazenamento simples de chave/valor auxiliar (sessões do Google etc.). */
    fun getString(k: String): String? = sp.getString("x_$k", null)
    fun putString(k: String, v: String?) = sp.edit().putString("x_$k", v).apply()
    fun getLong(k: String, d: Long = 0) = sp.getLong("x_$k", d)
    fun putLong(k: String, v: Long) = sp.edit().putLong("x_$k", v).apply()
}
