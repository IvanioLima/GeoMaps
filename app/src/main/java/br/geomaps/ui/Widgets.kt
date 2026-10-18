package br.geomaps.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.geomaps.core.CoordFormat
import br.geomaps.data.Settings
import br.geomaps.location.Fix
import br.geomaps.map.CameraInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Bússola flutuante: a agulha aponta o norte do mapa; toque volta o norte para cima. */
@Composable
fun CompassWidget(bearing: Double, onClick: () -> Unit) {
    Surface(
        shape = CircleShape, color = Color.White.copy(alpha = 0.92f), shadowElevation = 3.dp,
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(40.dp).rotate(-bearing.toFloat())) {
                val cx = size.width / 2; val cy = size.height / 2
                val w = size.width * 0.16f; val h = size.height * 0.40f
                val north = Path().apply { moveTo(cx, cy - h); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }
                val south = Path().apply { moveTo(cx, cy + h); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }
                drawPath(north, Color(0xFFD32F2F))
                drawPath(south, Color(0xFF455A64))
                drawCircle(Color.White, radius = w * 0.45f, center = Offset(cx, cy))
            }
            Text("N", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 0.dp).rotate(0f))
        }
    }
}

/** Escala gráfica (metros/quilômetros). */
@Composable
fun ScaleBar(cam: CameraInfo, useKm: Boolean) {
    val density = LocalDensity.current.density
    val mpp = cam.metersPerPixel
    if (mpp <= 0 || mpp.isNaN()) return
    val targetPx = 90 * density
    val raw = mpp * targetPx
    val p = 10.0.pow(floor(log10(raw)))
    val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.last { it <= raw }
    val widthDp = (nice / mpp / density).toFloat()
    val label = if (nice >= 1000 && useKm) "${(nice / 1000).let { if (it == floor(it)) it.toInt().toString() else it.toString() }} km" else "${nice.toInt()} m"
    Column(horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 10.sp, color = Color.Black, modifier = Modifier.background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp))
        Canvas(Modifier.width(widthDp.dp).height(6.dp)) {
            val s = Stroke(width = 2.dp.toPx())
            drawLine(Color.Black, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = s.width)
            drawLine(Color.Black, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = s.width)
            drawLine(Color.Black, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = s.width)
        }
    }
}

/** Caixa com a coordenada atual (GPS) ou do centro do mapa. */
@Composable
fun CoordBox(fix: Fix?, cam: CameraInfo, settings: Settings, onClick: () -> Unit) {
    val lat = fix?.lat ?: cam.lat
    val lon = fix?.lon ?: cam.lon
    val lines = CoordFormat.formatLines(lat, lon, settings.coordPrefs)
    Surface(
        color = Color.White.copy(alpha = 0.85f), shape = RoundedCornerShape(6.dp), shadowElevation = 2.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
            Text(if (fix != null) "GPS" + (fix.accuracy?.let { " ±${it.toInt()} m" } ?: "") else "Centro do mapa",
                fontSize = 9.sp, color = if (fix != null) Color(0xFF2E7D32) else Color.DarkGray)
            for (l in lines) Text(l, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.Black, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TelemetryPanel(fix: Fix?, sats: Pair<Int, Int>, status: String, heading: Float?, settings: Settings) {
    Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(8.dp).width(170.dp)) {
            @Composable
            fun row(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = Color(0xFFB0BEC5), fontSize = 11.sp)
                Text(v, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text("Telemetria", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (fix == null) {
                Text(status, color = Color.White, fontSize = 11.sp)
            } else {
                row("Lat", String.format(Locale.US, "%.6f", fix.lat))
                row("Lon", String.format(Locale.US, "%.6f", fix.lon))
                row("Velocidade", fix.speed?.let { String.format(Locale.US, "%.1f km/h", it * 3.6) } ?: "—")
                row("Altitude", fix.altitude?.let { String.format(Locale.US, "%.1f m", it) + if (fix.altMsl == null) " (elip.)" else "" } ?: "—")
                row("Precisão", fix.accuracy?.let { "±%.1f m".format(Locale.US, it) } ?: "—")
                row("Satélites", "${sats.first}/${sats.second}")
                row("Rumo", heading?.let { "%.0f°".format(Locale.US, it) } ?: "—")
                row("Fonte", fix.quality ?: fix.source)
            }
        }
    }
}

@Composable
fun RoundButton(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.secondary else Color(0xFF263238).copy(alpha = 0.88f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(52.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(26.dp)) }
    }
}

/** Mira no centro do mapa (usada nos modos de desenho e edição). */
@Composable
fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(modifier.size(36.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val l = size.width / 2
        drawLine(Color.White, Offset(c.x - l, c.y), Offset(c.x + l, c.y), strokeWidth = 5f)
        drawLine(Color.White, Offset(c.x, c.y - l), Offset(c.x, c.y + l), strokeWidth = 5f)
        drawLine(Color(0xFFD50000), Offset(c.x - l, c.y), Offset(c.x + l, c.y), strokeWidth = 2.2f)
        drawLine(Color(0xFFD50000), Offset(c.x, c.y - l), Offset(c.x, c.y + l), strokeWidth = 2.2f)
    }
}

/** Miniatura de foto carregada do armazenamento interno. */
@Composable
fun PhotoThumb(file: File, sizeDp: Int = 72, onClick: () -> Unit) {
    var bmp by remember(file.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(file.path) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                var s = 1
                while (o.outWidth / s > sizeDp * 6) s *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray)
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        bmp?.let {
            androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(sizeDp.dp))
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))
