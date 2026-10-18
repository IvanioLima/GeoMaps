package br.geomaps.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.geomaps.core.Coord
import br.geomaps.core.CoordFormat
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeoMath
import br.geomaps.core.Geom
import br.geomaps.core.GeomType
import br.geomaps.core.Stats
import br.geomaps.data.Settings
import br.geomaps.location.Fix
import br.geomaps.location.RecState
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun BarCard(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large, shadowElevation = 6.dp, tonalElevation = 2.dp,
    ) { Column(Modifier.padding(12.dp)) { content() } }
}

@Composable
fun ScrollRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
fun DrawBar(vm: MainViewModel, m: Mode.Draw, s: Settings) {
    BarCard {
        val poly = m.type == GeomType.POLYGON
        Text(if (poly) "Nova área" else "Nova trilha", fontWeight = FontWeight.Bold)
        val len = GeoMath.length(if (poly && m.pts.size >= 3) m.pts + m.pts.first() else m.pts)
        var info = "${m.pts.size} vértices · ${if (poly) "perímetro" else "comprimento"} ${CoordFormat.distance(len, s.useKm)}"
        if (poly && m.pts.size >= 3) info += " · área " + CoordFormat.area(GeoMath.area(Geom.Poly.of(m.pts), s.fixedZone, if (s.fixedZone != null) s.fixedSouth else null), s.useKm)
        Text(info, style = MaterialTheme.typography.bodySmall)
        Gap(6)
        ScrollRow {
            AssistChip(onClick = { vm.drawAddCenter() }, label = { Text("+ Centro") })
            AssistChip(onClick = { vm.dialog.value = Dialog.CoordInput("draw") }, label = { Text("Coordenada") })
            AssistChip(onClick = { vm.drawUndo() }, label = { Text("Desfazer vértice") })
            TextButton(onClick = { vm.cancelMode() }) { Text("Cancelar") }
            Button(onClick = { vm.finishDraw() }) { Text("Concluir") }
        }
    }
}

@Composable
fun EditBar(vm: MainViewModel, m: Mode.EditVertices) {
    BarCard {
        Text(if (m.isPoint) "Mover ponto" else "Editar vértices", fontWeight = FontWeight.Bold)
        Text(
            if (m.isPoint) "Toque no mapa ou use a mira e \"Mover p/ centro\""
            else "${m.pts.size} vértices · ${m.selected.size} selecionado(s)" + if (m.rangeMode) " · toque no fim do intervalo" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Gap(6)
        ScrollRow {
            AssistChip(onClick = { vm.vertexMoveToCenter() }, label = { Text("Mover p/ centro") })
            if (!m.isPoint) {
                AssistChip(onClick = { vm.vertexInsertAtCenter() }, label = { Text("Inserir no centro") })
                AssistChip(onClick = { vm.vertexDelete() }, label = { Text("Excluir") })
                AssistChip(onClick = { vm.vertexRange() }, label = { Text("Intervalo") })
                AssistChip(onClick = { vm.vertexSelectAll() }, label = { Text("Todos") })
                AssistChip(onClick = { vm.editNextPart() }, label = { Text("Próx. segmento") })
            }
            AssistChip(onClick = { vm.dialog.value = Dialog.CoordInput("vertex") }, label = { Text(if (m.isPoint) "Coordenada" else "Inserir por coordenada") })
            TextButton(onClick = { vm.cancelMode() }) { Text("Cancelar") }
            Button(onClick = { vm.commitVertices() }) { Text("Salvar") }
        }
    }
}

@Composable
fun MeasureBar(vm: MainViewModel, m: Mode.Measure, s: Settings) {
    BarCard {
        Text("Medição", fontWeight = FontWeight.Bold)
        val len = GeoMath.length(m.pts)
        Text("Distância: ${CoordFormat.distance(len, s.useKm)}", fontFamily = FontFamily.Monospace)
        if (m.area && m.pts.size >= 3) {
            Text("Perímetro: ${CoordFormat.distance(GeoMath.length(m.pts + m.pts.first()), s.useKm)}", fontFamily = FontFamily.Monospace)
            Text("Área: ${CoordFormat.area(GeoMath.area(Geom.Poly.of(m.pts)), s.useKm)}", fontFamily = FontFamily.Monospace)
        }
        if (m.pts.size >= 2) {
            val b = GeoMath.bearing(m.pts[m.pts.size - 2], m.pts.last())
            Text("Rumo do último trecho: %.1f°".format(Locale.US, b), style = MaterialTheme.typography.bodySmall)
        }
        Gap(6)
        ScrollRow {
            FilterChip(selected = m.area, onClick = { vm.mode.value = m.copy(area = !m.area) }, label = { Text("Área") })
            AssistChip(onClick = { vm.drawAddCenter() }, label = { Text("+ Centro") })
            AssistChip(onClick = { vm.dialog.value = Dialog.CoordInput("measure") }, label = { Text("Coordenada") })
            AssistChip(onClick = { vm.drawUndo() }, label = { Text("Desfazer") })
            AssistChip(onClick = { vm.mode.value = Mode.Measure(area = m.area) }, label = { Text("Limpar") })
            Button(onClick = { vm.cancelMode() }) { Text("Fechar") }
        }
    }
}

@Composable
fun SplitBar(vm: MainViewModel, m: Mode.Split) {
    BarCard {
        Text("Dividir trilha", fontWeight = FontWeight.Bold)
        Text("${m.cuts.size} ponto(s) de divisão marcado(s) → ${m.cuts.size + 1} partes", style = MaterialTheme.typography.bodySmall)
        Gap(6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.cancelMode() }) { Text("Cancelar") }
            Button(onClick = { vm.confirmSplit() }, enabled = m.cuts.isNotEmpty()) { Text("Dividir") }
        }
    }
}

@Composable
fun PickAreaBar(vm: MainViewModel, m: Mode.PickArea) {
    BarCard {
        Text(if (m.purpose == "dem") "Área do modelo de elevação" else "Área para mapa offline", fontWeight = FontWeight.Bold)
        Text(if (m.free) "Toque para marcar o contorno (${m.pts.size} pontos)" else "Toque em dois cantos opostos (${m.pts.size}/2)", style = MaterialTheme.typography.bodySmall)
        Gap(6)
        ScrollRow {
            AssistChip(onClick = { vm.drawAddCenter() }, label = { Text("+ Centro") })
            AssistChip(onClick = { vm.drawUndo() }, label = { Text("Desfazer") })
            TextButton(onClick = { vm.cancelMode() }) { Text("Cancelar") }
            Button(onClick = { vm.confirmPickArea() }) { Text("Continuar") }
        }
    }
}

@Composable
fun RecordingPanel(vm: MainViewModel, rec: RecState, s: Settings) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.active, rec.paused) {
        while (rec.active) { now = System.currentTimeMillis(); delay(1000) }
    }
    val elapsed = rec.elapsedNow().let { if (now > 0) it else it }
    BarCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (rec.paused) Color(0xFFFFA000) else Color(0xFFD32F2F), modifier = Modifier.size(12.dp)) {}
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (rec.paused) "Gravação pausada" else "Gravando trilha", fontWeight = FontWeight.Bold)
                Text("${s.gpsProfile.label} · ${rec.points} pontos", style = MaterialTheme.typography.bodySmall)
            }
        }
        Gap(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            @Composable
            fun stat(v: String, l: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(l, style = MaterialTheme.typography.labelSmall)
            }
            stat(CoordFormat.duration(elapsed), "Tempo")
            stat(rec.speed?.let { "%.1f".format(Locale.US, it * 3.6) } ?: "—", "Velocidade (km/h)")
            stat(CoordFormat.distance(rec.distance, s.useKm), "Distância")
        }
        Gap(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rec.paused) Button(onClick = { vm.resumeRecording() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null); Text(" Continuar")
            } else OutlinedButton(onClick = { vm.pauseRecording() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Pause, null); Text(" Pausar")
            }
            Button(
                onClick = { vm.pauseRecording(); vm.dialog.value = Dialog.FinishRecording },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary), modifier = Modifier.weight(1f),
            ) { Icon(Icons.Default.Stop, null); Text(" Concluir") }
        }
    }
}

@Composable
fun NavPanel(vm: MainViewModel, n: NavState, fix: Fix?, heading: Float?, s: Settings) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp), shape = MaterialTheme.shapes.large,
        color = Color(0xFF263238).copy(alpha = 0.92f), shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            when (n) {
                is NavState.GoTo -> {
                    val dist = fix?.let { GeoMath.distance(it.lat, it.lon, n.target.lat, n.target.lon) }
                    val brg = fix?.let { GeoMath.bearing(it.lat, it.lon, n.target.lat, n.target.lon) }
                    val rel = if (brg != null) (brg - (heading ?: 0f) + 360) % 360 else 0.0
                    Icon(Icons.Default.Navigation, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(46.dp).rotate(rel.toFloat()))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Indo até: ${n.name}", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (dist == null) "Aguardando GPS…" else "${CoordFormat.distance(dist, s.useKm)} · rumo ${"%.0f".format(Locale.US, brg)}°" +
                                (fix?.speed?.takeIf { it > 0.5f }?.let { sp -> " · ${CoordFormat.duration((dist / sp * 1000).toLong())}" } ?: ""),
                            color = Color.White, fontSize = 14.sp,
                        )
                        if (dist != null && dist < 10) Text("Você chegou!", color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold)
                    }
                }
                is NavState.FollowTrack -> {
                    val near = fix?.let { GeoMath.nearestOnLine(Coord(it.lat, it.lon), n.line) }
                    val total = n.line.sumOf { GeoMath.length(it) }
                    Column(Modifier.weight(1f)) {
                        Text("Seguindo: ${n.name}", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (near == null) Text("Aguardando GPS…", color = Color.White)
                        else {
                            val off = near.dist > s.deviationAlertM
                            Text("Desvio: ${CoordFormat.distance(near.dist, s.useKm)}", color = if (off) Color(0xFFFF5252) else Color(0xFF69F0AE), fontWeight = FontWeight.Bold)
                            Text("Restante: ${CoordFormat.distance((total - near.alongDist).coerceAtLeast(0.0), s.useKm)} de ${CoordFormat.distance(total, s.useKm)}", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
            IconButton(onClick = { vm.stopNav() }) { Icon(Icons.Default.Close, "Parar", tint = Color.White) }
        }
    }
}

fun copyToClipboard(ctx: Context, label: String, value: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

@Composable
fun FeatureSheet(vm: MainViewModel, f: GeoFeature, s: Settings) {
    val ctx = LocalContext.current
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    BarCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.name.ifBlank { "(sem nome)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val proj = vm.projects.value.firstOrNull { it.id == f.projectId }?.name ?: ""
                Text("${f.type.label}${if (f.category.isNotBlank()) " · " + f.category else ""} · $proj", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { vm.selectedId.value = null }) { Icon(Icons.Default.Close, "Fechar") }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
            val rows = ArrayList<Pair<String, String>>()
            if (f.description.isNotBlank()) rows += "Descrição" to f.description.replace(Regex("<[^>]+>"), " ").trim()
            when (val g = f.geom) {
                is Geom.Pt -> {
                    rows += "Coordenada" to CoordFormat.format(g.c.lat, g.c.lon, s.coordPrefs)
                    if (s.coordSystem != br.geomaps.core.CoordSystem.DD) rows += "Lat, Lon" to CoordFormat.dd(g.c.lat, g.c.lon)
                    g.c.ele?.let { rows += "Altitude" to "%.2f m".format(Locale.US, it) }
                    g.c.time?.let { rows += "Data" to SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(it) }
                    vm.fix.value?.let { fx -> rows += "Distância daqui" to CoordFormat.distance(GeoMath.distance(fx.lat, fx.lon, g.c.lat, g.c.lon), s.useKm) }
                }
                is Geom.Line -> {
                    rows += "Comprimento" to CoordFormat.distance(GeoMath.length(g), s.useKm)
                    rows += "Vértices" to g.allCoords().size.toString() + if (g.parts.size > 1) " em ${g.parts.size} segmentos" else ""
                    val st = Stats.track(g.parts)
                    st.duration?.let { rows += "Duração" to CoordFormat.duration(it) }
                    if (st.maxEle != null) rows += "Altitude mín./máx." to "%.0f / %.0f m".format(Locale.US, st.minEle, st.maxEle)
                }
                is Geom.Poly -> {
                    rows += "Área" to CoordFormat.area(GeoMath.area(g, s.fixedZone, if (s.fixedZone != null) s.fixedSouth else null), s.useKm)
                    rows += "Perímetro" to CoordFormat.distance(GeoMath.perimeter(g), s.useKm)
                    rows += "Vértices" to g.allCoords().size.toString()
                }
            }
            for ((k, v) in f.attrs) rows += k to v
            for ((k, v) in rows) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        copyToClipboard(ctx, k, v)
                        vm.toast("Copiado: $k")
                    }.padding(vertical = 5.dp),
                ) {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(118.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (f.photos.isNotEmpty()) {
                Gap(4)
                ScrollRow {
                    for (p in f.photos) PhotoThumb(vm.app.media.photoFile(p)) { vm.dialog.value = Dialog.Photo(p) }
                }
            }
            Text("Toque num atributo para copiar", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
        }
        Gap(6)
        ScrollRow {
            AssistChip(onClick = {
                vm.dialog.value = if (f.type == GeomType.POINT) Dialog.Waypoint(f) else Dialog.FeatureProps(f)
            }, label = { Text("Editar") })
            AssistChip(onClick = { vm.startEditVertices(f.id) }, label = { Text(if (f.type == GeomType.POINT) "Mover" else "Vértices") })
            AssistChip(onClick = { vm.zoomTo(f) }, label = { Text("Zoom") })
            if (f.type == GeomType.POINT) AssistChip(onClick = { vm.goTo(f) }, label = { Text("Ir até") })
            if (f.type != GeomType.POINT) AssistChip(onClick = { vm.showProfile(f.id) }, label = { Text("Perfil") })
            if (f.type == GeomType.LINE) {
                AssistChip(onClick = { vm.dialog.value = Dialog.Stats(f.id) }, label = { Text("Estatísticas") })
                AssistChip(onClick = { vm.followTrack(f) }, label = { Text("Seguir") })
                AssistChip(onClick = { vm.dialog.value = Dialog.Merge(f.id) }, label = { Text("Mesclar") })
                AssistChip(onClick = { vm.startSplit(f.id) }, label = { Text("Dividir") })
                AssistChip(onClick = { vm.dialog.value = Dialog.Reverse(f.id) }, label = { Text("Inverter") })
                AssistChip(onClick = { vm.dialog.value = Dialog.FilterGps(f.id) }, label = { Text("Filtrar GPS") })
            }
            if (f.type != GeomType.POINT) AssistChip(onClick = { vm.dialog.value = Dialog.Simplify(f.id) }, label = { Text("Simplificar") })
            AssistChip(onClick = { vm.dialog.value = Dialog.Buffer(f.id) }, label = { Text("Buffer") })
            AssistChip(onClick = { vm.duplicateFeature(f.id) }, label = { Text("Duplicar") })
            AssistChip(onClick = { vm.dialog.value = Dialog.MoveToProject(f.id) }, label = { Text("Mover p/ projeto") })
            AssistChip(onClick = { vm.dialog.value = Dialog.Export(null, listOf(f.id), f.name) }, label = { Text("Exportar/Compartilhar") })
            AssistChip(onClick = {
                vm.dialog.value = Dialog.Confirm("Excluir", "Excluir \"${f.name}\"? (pode ser desfeito)") { vm.deleteFeature(f.id) }
            }, label = { Text("Excluir", color = MaterialTheme.colorScheme.error) })
        }
    }
}

@Composable
fun ProfilePanel(vm: MainViewModel, p: ProfileState, s: Settings) {
    BarCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Perfil de elevação", fontWeight = FontWeight.Bold)
                Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.closeProfile() }) { Icon(Icons.Default.Close, "Fechar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !p.useDem, onClick = { vm.setProfileSource(false) }, label = { Text("Altitude do GPS") }, enabled = p.recorded)
            FilterChip(selected = p.useDem, onClick = { if (p.demAvailable) vm.setProfileSource(true) else vm.toast("Baixe o MDE desta região em Mapas offline") },
                label = { Text("Modelo de elevação" + if (!p.demAvailable) " (não baixado)" else "") })
        }
        val prof = p.profile
        if (prof == null) {
            Text("Sem dados de altitude para esta trilha.", modifier = Modifier.padding(8.dp))
            return@BarCard
        }
        val minE = prof.ele.minOrNull() ?: 0.0
        val maxE = prof.ele.maxOrNull() ?: 0.0
        val (up, down) = Stats.ascentDescent(prof.ele.toList(), if (p.useDem) 2.0 else 4.0)
        val hi = p.highlight
        Text(
            if (hi != null) "Distância ${CoordFormat.distance(prof.dist[hi], s.useKm)} · altitude %.1f m".format(Locale.US, prof.ele[hi])
            else "Toque ou arraste no gráfico para localizar no mapa",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        )
        val lineColor = MaterialTheme.colorScheme.primary
        val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        Box(Modifier.fillMaxWidth().height(150.dp).padding(top = 4.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(150.dp)
                    .pointerInput(prof) {
                        detectTapGestures { o -> vm.profileHighlight(prof.indexAt(o.x / size.width * prof.total)) }
                    }
                    .pointerInput(prof) {
                        detectDragGestures { ch, _ -> vm.profileHighlight(prof.indexAt((ch.position.x / size.width).coerceIn(0f, 1f) * prof.total)) }
                    },
            ) {
                val pad = 4f
                val range = (maxE - minE).coerceAtLeast(1.0)
                val w = size.width; val h = size.height - pad * 2
                fun x(d: Double) = (d / prof.total * w).toFloat()
                fun y(e: Double) = (pad + h - (e - minE) / range * h).toFloat()
                val path = Path()
                val fill = Path()
                fill.moveTo(0f, size.height)
                for (i in 0 until prof.size) {
                    val px = x(prof.dist[i]); val py = y(prof.ele[i])
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    fill.lineTo(px, py)
                }
                fill.lineTo(w, size.height); fill.close()
                drawPath(fill, fillColor)
                drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx()))
                for (k in 1..3) {
                    val yy = pad + h * k / 4f
                    drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, yy), Offset(w, yy))
                }
                hi?.let { i ->
                    val px = x(prof.dist[i])
                    drawLine(Color(0xFFFF6D00), Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
                    drawCircle(Color(0xFFFFD600), 6.dp.toPx(), Offset(px, y(prof.ele[i])))
                }
            }
            Text("%.0f m".format(Locale.US, maxE), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart))
            Text("%.0f m".format(Locale.US, minE), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total ${CoordFormat.distance(prof.total, s.useKm)}", style = MaterialTheme.typography.labelSmall)
            Text("↑ %.0f m  ↓ %.0f m".format(Locale.US, up, down), style = MaterialTheme.typography.labelSmall)
            Text("mín %.0f · máx %.0f m".format(Locale.US, minE, maxE), style = MaterialTheme.typography.labelSmall)
        }
        if (p.useDem) Text("Fonte: ${if (s.demSource == "srtm") "SRTM 1\"" else "Copernicus GLO-30"} (altitude ortométrica)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseMapSheet(vm: MainViewModel, s: Settings, onClose: () -> Unit) {
    val sources by vm.customSources.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Mapa base", style = MaterialTheme.typography.titleLarge)
            val bases = vm.allBases()
            for (g in bases.map { it.group }.distinct()) {
                Text(g, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                for (b in bases.filter { it.group == g }) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setBase(b.id); onClose() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = s.baseMap == b.id, onClick = { vm.setBase(b.id); onClose() })
                        Column(Modifier.weight(1f)) {
                            Text(b.name)
                            val note = when {
                                b.needsKey == "google" && s.googleKey.isBlank() -> "Requer chave da Map Tiles API (Configurações) · só online"
                                b.needsKey == "google" -> "Somente online (cache conforme cabeçalhos)"
                                b.id == "esri" -> "Offline pelo serviço oficial de exportação (chave ArcGIS)"
                                b.id == "osm_vec" -> "Baixe regiões em Mapas offline"
                                b.offline -> "Permite download offline"
                                else -> "Somente online"
                            }
                            Text(note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
            val overlays = sources.filter { it.overlay }
            if (overlays.isNotEmpty()) {
                Text("Sobreposições", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                for (o in overlays) {
                    val id = "c_${o.id}"
                    Row(Modifier.fillMaxWidth().clickable { vm.toggleOverlay(id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = id in s.overlays, onCheckedChange = { vm.toggleOverlay(id) })
                        Text(o.name)
                    }
                }
            }
            Gap(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onClose(); vm.screen.value = Screen.OFFLINE }) { Text("Mapas offline") }
                OutlinedButton(onClick = { onClose(); vm.screen.value = Screen.SOURCES }) { Text("Camadas personalizadas") }
            }
        }
    }
}

@Suppress("unused")
private fun fileOf(vm: MainViewModel, p: String): File = vm.app.media.photoFile(p)
