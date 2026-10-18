package br.geomaps.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bússola (azimute em relação ao norte verdadeiro), corrigida pela rotação da tela. */
class Compass(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _azimuth = MutableStateFlow<Float?>(null)
    val azimuth: StateFlow<Float?> = _azimuth
    private var users = 0
    private var declination = 0f
    private var smooth: Float? = null
    val available get() = rotation != null

    fun setLocation(lat: Double, lon: Double, alt: Double) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis()).declination
    }

    @Synchronized
    fun acquire() {
        users++
        if (users == 1 && rotation != null) sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) sm.unregisterListener(this)
    }

    private val rm = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        @Suppress("DEPRECATION")
        val rot = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val (ax, ay) = when (rot) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rm, ax, ay, remapped)
        SensorManager.getOrientation(remapped, orient)
        var az = (Math.toDegrees(orient[0].toDouble()).toFloat() + declination + 360f) % 360f
        // suavização circular
        val s = smooth
        if (s != null) {
            var d = az - s
            if (d > 180) d -= 360
            if (d < -180) d += 360
            az = (s + d * 0.25f + 360f) % 360f
        }
        smooth = az
        _azimuth.value = az
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
