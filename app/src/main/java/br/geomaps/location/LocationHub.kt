package br.geomaps.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import br.geomaps.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Posição consolidada do GPS do aparelho. */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** Altitude ortométrica (nível do mar) quando conhecida. */
    val altMsl: Double?,
    /** Altitude elipsoidal bruta (GPS). */
    val altEllipsoid: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    val time: Long,
    val satellites: Int,
    val source: String,
    val quality: String? = null,
) {
    /** Altitude preferencial para exibir/gravar. */
    val altitude: Double? get() = altMsl ?: altEllipsoid
}

/**
 * Concentra as fontes de posição. Componentes "adquirem" o GPS (mapa visível, gravação, navegação)
 * e ele só fica ligado enquanto houver alguém usando — economiza bateria.
 */
class LocationHub(private val context: Context, private val settings: SettingsStore) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private val _status = MutableStateFlow("GPS desligado")
    val status: StateFlow<String> = _status
    private val _satellites = MutableStateFlow(0 to 0)
    /** (usados no fix, visíveis) */
    val satellites: StateFlow<Pair<Int, Int>> = _satellites

    private val users = HashSet<String>()
    private var running = false
    private var runningProfile: Pair<Long, Float>? = null
    private var geoidSep: Double? = null
    private var lastGpsFix = 0L
    private var altConverter: Any? = null

    init {
        scope.launch {
            settings.flow.map { it.intervalMs to it.distanceM }.distinctUntilChanged().collect {
                if (running) { stop(); start() }
            }
        }
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun acquire(tag: String) {
        users += tag
        if (!running) start()
    }

    @Synchronized
    fun release(tag: String) {
        users -= tag
        if (users.isEmpty() && running) stop()
    }

    /** Reinicia após conceder permissão. */
    fun restart() {
        if (users.isNotEmpty()) { stop(); start() }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) _status.value = "GPS desativado nas configurações do aparelho"
        }
        @Deprecated("API antiga")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // só usa a rede enquanto não houver GPS recente
            if (System.currentTimeMillis() - lastGpsFix > 15000) onLocation(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("API antiga")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        // GGA campo 11 = separação geoidal
        if (message.length > 6 && message.substring(3, 6) == "GGA") {
            val f = message.split(',')
            f.getOrNull(11)?.toDoubleOrNull()?.let { geoidSep = it }
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _satellites.value = used to status.satelliteCount
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!hasPermission()) { _status.value = "Sem permissão de localização"; return }
        running = true
        val s = settings.value
        runningProfile = s.intervalMs to s.distanceM
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, s.intervalMs, s.distanceM, listener, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, netListener, Looper.getMainLooper())
            }
            lm.addNmeaListener(nmeaListener, main)
            lm.registerGnssStatusCallback(gnssCallback, main)
            _status.value = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Procurando satélites…" else "GPS desativado nas configurações do aparelho"
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { if (_fix.value == null) onLocation(it) }
        } catch (e: Exception) {
            _status.value = "Erro no GPS: ${e.message}"
        }
    }

    private fun stop() {
        running = false
        runCatching { lm.removeUpdates(listener) }
        runCatching { lm.removeUpdates(netListener) }
        runCatching { lm.removeNmeaListener(nmeaListener) }
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        _status.value = "GPS desligado"
    }

    private fun onLocation(l: Location) {
        if (l.provider == LocationManager.GPS_PROVIDER) lastGpsFix = System.currentTimeMillis()
        val ellips = if (l.hasAltitude()) l.altitude else null
        var msl: Double? = null
        if (Build.VERSION.SDK_INT >= 34) {
            if (!l.hasMslAltitude() && ellips != null) {
                runCatching {
                    val conv = (altConverter as? android.location.altitude.AltitudeConverter)
                        ?: android.location.altitude.AltitudeConverter().also { altConverter = it }
                    // A conversão pode ler um arquivo de modelo: faz em segundo plano e atualiza depois
                    val copy = Location(l)
                    Thread {
                        runCatching {
                            conv.addMslAltitudeToLocation(context, copy)
                            if (copy.hasMslAltitude()) main.post { publish(copy, copy.mslAltitudeMeters, ellips) }
                        }
                    }.start()
                }
            } else if (l.hasMslAltitude()) msl = l.mslAltitudeMeters
        }
        if (msl == null && ellips != null && geoidSep != null) msl = ellips - geoidSep!!
        publish(l, msl, ellips)
    }

    private fun publish(l: Location, msl: Double?, ellips: Double?) {
        _fix.value = Fix(
            lat = l.latitude, lon = l.longitude, altMsl = msl, altEllipsoid = ellips,
            accuracy = if (l.hasAccuracy()) l.accuracy else null,
            speed = if (l.hasSpeed()) l.speed else null,
            bearing = if (l.hasBearing()) l.bearing else null,
            time = l.time, satellites = _satellites.value.first,
            source = if (l.provider == LocationManager.NETWORK_PROVIDER) "Rede" else "GPS interno",
        )
        _status.value = if (l.provider == LocationManager.NETWORK_PROVIDER) "Posição aproximada (rede)" else "GPS ativo"
    }
}
