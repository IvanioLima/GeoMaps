package br.geomaps.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.geomaps.GeoMapsApp
import br.geomaps.MainActivity
import br.geomaps.core.Coord
import br.geomaps.core.CoordFormat
import br.geomaps.core.GeoMath
import br.geomaps.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RecState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val segments: List<List<Coord>> = emptyList(),
    val distance: Double = 0.0,
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val speed: Float? = null,
    val startedAt: Long = 0,
) {
    val points get() = segments.sumOf { it.size }
    fun elapsedNow() = elapsedMs + if (active && !paused && resumedAt > 0) System.currentTimeMillis() - resumedAt else 0
}

/** Estado da gravação de trilha (persistido no banco a cada ponto, sobrevive ao fechamento do app). */
class TrackRecorder(private val db: Db) {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state
    var maxAccuracy = 50f

    /** Recupera uma gravação interrompida (ex.: o sistema encerrou o app). */
    fun restore(): Boolean {
        val pts = db.recPoints()
        if (pts.isEmpty()) return false
        val segs = pts.groupBy { it.first }.toSortedMap().values.map { l -> l.map { it.second } }
        _state.value = RecState(active = true, paused = true, segments = segs, distance = segs.sumOf { GeoMath.length(it) },
            elapsedMs = pts.mapNotNull { it.second.time }.let { if (it.size > 1) it.last() - it.first() else 0 }, startedAt = pts.first().second.time ?: System.currentTimeMillis())
        return true
    }

    fun start() {
        db.recClear()
        val now = System.currentTimeMillis()
        _state.value = RecState(active = true, paused = false, segments = listOf(emptyList()), resumedAt = now, startedAt = now)
    }

    fun pause() {
        val s = _state.value
        if (!s.active || s.paused) return
        _state.value = s.copy(paused = true, elapsedMs = s.elapsedNow(), resumedAt = 0)
    }

    fun resume() {
        val s = _state.value
        if (!s.active || !s.paused) return
        _state.value = s.copy(paused = false, resumedAt = System.currentTimeMillis(), segments = s.segments + listOf(emptyList()))
    }

    /** Encerra e devolve os segmentos gravados. */
    fun finish(): List<List<Coord>> {
        val segs = _state.value.segments.filter { it.size >= 2 }
        _state.value = RecState()
        db.recClear()
        return segs
    }

    fun discard() {
        _state.value = RecState()
        db.recClear()
    }

    fun onFix(f: Fix) {
        val s = _state.value
        if (!s.active || s.paused) { _state.value = s.copy(speed = f.speed); return }
        if (f.source == "Rede") return
        if (f.accuracy != null && f.accuracy > maxAccuracy) { _state.value = s.copy(speed = f.speed); return }
        val c = Coord(f.lat, f.lon, f.altitude, f.time, f.accuracy?.toDouble())
        val segs = s.segments.toMutableList()
        if (segs.isEmpty()) segs += emptyList<Coord>()
        val cur = segs.last()
        var add = 0.0
        val last = cur.lastOrNull()
        if (last != null) {
            val d = GeoMath.distance(last, c)
            val dt = ((c.time ?: 0) - (last.time ?: 0)) / 1000.0
            // descarta saltos impossíveis (> 300 km/h) e pontos repetidos
            if (dt > 0 && d / dt > 83) return
            if (d < 0.5 && dt < 30) { _state.value = s.copy(speed = f.speed); return }
            add = d
        }
        segs[segs.size - 1] = cur + c
        db.recAdd(segs.size - 1, c)
        _state.value = s.copy(segments = segs, distance = s.distance + add, speed = f.speed)
    }
}

class TrackRecordingService : Service() {
    companion object {
        const val CHANNEL = "gravacao"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val NOTIF_ID = 42

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackRecordingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var acquired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notif = build("Iniciando gravação…")
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        val app = application as GeoMapsApp
        if (!acquired) {
            app.locationHub.acquire("recording")
            acquired = true
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoMaps:gravacao").apply { setReferenceCounted(false); acquire() }
        }
        if (job == null) {
            job = scope.launch {
                launch { app.locationHub.fix.collect { f -> if (f != null) app.recorder.onFix(f) } }
                while (true) {
                    val s = app.recorder.state.value
                    if (!s.active) break
                    val km = app.settings.value.useKm
                    val txt = (if (s.paused) "Pausada · " else "") + CoordFormat.distance(s.distance, km) + " · " + CoordFormat.duration(s.elapsedNow())
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(txt))
                    delay(5000)
                }
            }
        }
        return START_STICKY
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        if (acquired) (application as GeoMapsApp).locationHub.release("recording")
        acquired = false
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gravação de trilha", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra a gravação de trilha em andamento"
            })
        }
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("GeoMaps — gravando trilha")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
