package br.geomaps.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.geomaps.core.Coord
import br.geomaps.core.CoordFormat
import br.geomaps.core.CoordSystem
import br.geomaps.core.ExportFormat
import br.geomaps.core.FieldType
import br.geomaps.core.FormField
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeoMath
import br.geomaps.core.Geom
import br.geomaps.core.GeomType
import br.geomaps.core.Stats
import br.geomaps.core.TileMath
import br.geomaps.core.TrackOps
import br.geomaps.core.Utm
import br.geomaps.core.UtmCoord
import br.geomaps.data.IconFactory
import br.geomaps.data.Settings
import java.util.Locale

val PALETTE = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1, 0xFF00897B,
    0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E, 0xFF6D4C41, 0xFF546E7A, 0xFF000000, 0xFFFFFFFF,
).map { it.toInt() }

/** Diálogo amplo com rolagem, usado nos formulários. */
@Composable
fun FormDialog(
    title: String, onDismiss: () -> Unit, confirm: String = "Salvar", onConfirm: (() -> Unit)?,
    extraButton: (@Composable () -> Unit)? = null, content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).heightIn(max = 720.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Gap(10)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Gap(10)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    extraButton?.invoke()
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(if (onConfirm == null) "Fechar" else "Cancelar") }
                    if (onConfirm != null) Button(onClick = onConfirm) { Text(confirm) }
                }
            }
        }
    }
}

@Composable
fun NumField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Editor de coordenada nos três sistemas (UTM, graus decimais, GMS). */
@Composable
fun CoordEditor(initial: Coord, settings: Settings, onChange: (Coord?) -> Unit) {
    var sys by remember { mutableStateOf(settings.coordSystem) }
    var lat by remember { mutableStateOf(String.format(Locale.US, "%.7f", initial.lat)) }
    var lon by remember { mutableStateOf(String.format(Locale.US, "%.7f", initial.lon)) }
    val u0 = remember { CoordFormat.toUtm(initial.lat, initial.lon, settings.coordPrefs) }
    var zone by remember { mutableStateOf(u0.zone.toString()) }
    var south by remember { mutableStateOf(u0.south) }
    var e by remember { mutableStateOf(String.format(Locale.US, "%.3f", u0.e)) }
    var n by remember { mutableStateOf(String.format(Locale.US, "%.3f", u0.n)) }
    val d0 = remember { CoordFormat.toDms(initial.lat) to CoordFormat.toDms(initial.lon) }
    var latD by remember { mutableStateOf(d0.first.first.toString()) }
    var latM by remember { mutableStateOf(d0.first.second.toString()) }
    var latS by remember { mutableStateOf(String.format(Locale.US, "%.3f", d0.first.third)) }
    var latSouth by remember { mutableStateOf(initial.lat < 0) }
    var lonD by remember { mutableStateOf(d0.second.first.toString()) }
    var lonM by remember { mutableStateOf(d0.second.second.toString()) }
    var lonS by remember { mutableStateOf(String.format(Locale.US, "%.3f", d0.second.third)) }
    var lonWest by remember { mutableStateOf(initial.lon < 0) }

    fun compute(): Coord? = runCatching {
        when (sys) {
            CoordSystem.DD -> {
                val a = CoordFormat.num(lat)!!; val b = CoordFormat.num(lon)!!
                if (a !in -90.0..90.0 || b !in -180.0..180.0) null else Coord(a, b, initial.ele)
            }
            CoordSystem.DMS -> Coord(
                CoordFormat.fromDms(CoordFormat.num(latD)!!, CoordFormat.num(latM) ?: 0.0, CoordFormat.num(latS) ?: 0.0, latSouth),
                CoordFormat.fromDms(CoordFormat.num(lonD)!!, CoordFormat.num(lonM) ?: 0.0, CoordFormat.num(lonS) ?: 0.0, lonWest), initial.ele,
            )
            CoordSystem.UTM -> {
                val z = zone.trim().toInt()
                if (z !in 1..60) null else Utm.toLatLon(UtmCoord(z, south, CoordFormat.num(e)!!, CoordFormat.num(n)!!), settings.datum.ellipsoid)
                    .let { (a, b) -> Coord(a, b, initial.ele) }
            }
        }
    }.getOrNull()

    LaunchedEffect(sys, lat, lon, zone, south, e, n, latD, latM, latS, latSouth, lonD, lonM, lonS, lonWest) { onChange(compute()) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in CoordSystem.entries) FilterChip(selected = sys == s, onClick = {
                // sincroniza os campos do sistema escolhido com o valor atual
                compute()?.let { c ->
                    lat = String.format(Locale.US, "%.7f", c.lat); lon = String.format(Locale.US, "%.7f", c.lon)
                    val u = CoordFormat.toUtm(c.lat, c.lon, settings.coordPrefs)
                    zone = u.zone.toString(); south = u.south; e = String.format(Locale.US, "%.3f", u.e); n = String.format(Locale.US, "%.3f", u.n)
                    val (a1, a2, a3) = CoordFormat.toDms(c.lat); latD = a1.toString(); latM = a2.toString(); latS = String.format(Locale.US, "%.3f", a3); latSouth = c.lat < 0
                    val (b1, b2, b3) = CoordFormat.toDms(c.lon); lonD = b1.toString(); lonM = b2.toString(); lonS = String.format(Locale.US, "%.3f", b3); lonWest = c.lon < 0
                }
                sys = s
            }, label = { Text(when (s) { CoordSystem.UTM -> "UTM"; CoordSystem.DD -> "Graus dec."; CoordSystem.DMS -> "GMS" }) })
        }
        Gap(6)
        when (sys) {
            CoordSystem.DD -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Latitude", lat, Modifier.weight(1f)) { lat = it }
                NumField("Longitude", lon, Modifier.weight(1f)) { lon = it }
            }
            CoordSystem.UTM -> Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumField("Fuso", zone, Modifier.width(90.dp)) { zone = it }
                    FilterChip(selected = south, onClick = { south = true }, label = { Text("Sul") })
                    FilterChip(selected = !south, onClick = { south = false }, label = { Text("Norte") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("E (m)", e, Modifier.weight(1f)) { e = it }
                    NumField("N (m)", n, Modifier.weight(1f)) { n = it }
                }
                Text("Datum: ${settings.datum.label}", style = MaterialTheme.typography.labelSmall)
            }
            CoordSystem.DMS -> Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumField("Lat °", latD, Modifier.weight(1f)) { latD = it }
                    NumField("'", latM, Modifier.weight(0.8f)) { latM = it }
                    NumField("\"", latS, Modifier.weight(1.2f)) { latS = it }
                    TextButton(onClick = { latSouth = !latSouth }) { Text(if (latSouth) "S" else "N", fontWeight = FontWeight.Bold) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumField("Lon °", lonD, Modifier.weight(1f)) { lonD = it }
                    NumField("'", lonM, Modifier.weight(0.8f)) { lonM = it }
                    NumField("\"", lonS, Modifier.weight(1.2f)) { lonS = it }
                    TextButton(onClick = { lonWest = !lonWest }) { Text(if (lonWest) "W" else "E", fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (compute() == null) Text("Coordenada inválida", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(vm: MainViewModel, icon: String, color: Int, onIcon: (String) -> Unit, onColor: (Int) -> Unit) {
    Text("Ícone", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (e in IconFactory.EMOJIS) {
            val k = "e:$e"
            Box(
                Modifier.size(40.dp).border(if (icon == k) 2.dp else 0.5.dp, if (icon == k) MaterialTheme.colorScheme.secondary else Color.LightGray, RoundedCornerShape(8.dp))
                    .clickable { onIcon(k) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 20.sp) }
        }
        for (k in remember { vm.app.media.importedIcons() }) {
            val bmp = remember(k) { runCatching { vm.app.icons.bitmap(k, 0xFFFFFFFF.toInt(), 0.8f) }.getOrNull() }
            Box(
                Modifier.size(40.dp).border(if (icon == k) 2.dp else 0.5.dp, if (icon == k) MaterialTheme.colorScheme.secondary else Color.LightGray, RoundedCornerShape(8.dp))
                    .clickable { onIcon(k) },
                contentAlignment = Alignment.Center,
            ) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(30.dp)) } }
        }
    }
    Gap(8)
    ColorPicker("Cor", color, onColor)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(label: String, color: Int, onColor: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (c in PALETTE) {
            val sel = (c or 0xFF000000.toInt()) == (color or 0xFF000000.toInt())
            Box(
                Modifier.size(30.dp).background(Color(c), CircleShape)
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.secondary else Color.Gray, CircleShape)
                    .clickable { onColor(c) },
            )
        }
    }
}

@Composable
fun FormFields(form: List<FormField>, attrs: MutableMap<String, String>) {
    for (fld in form) {
        val v = attrs[fld.name] ?: ""
        when (fld.type) {
            FieldType.TEXT, FieldType.DATE -> OutlinedTextField(v, { attrs[fld.name] = it }, label = { Text(fld.name + if (fld.required) " *" else "") },
                placeholder = { if (fld.type == FieldType.DATE) Text("dd/mm/aaaa") }, modifier = Modifier.fillMaxWidth())
            FieldType.NUMBER -> NumField(fld.name + if (fld.required) " *" else "", v, Modifier.fillMaxWidth()) { attrs[fld.name] = it }
            FieldType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fld.name, Modifier.weight(1f))
                Switch(checked = v == "Sim", onCheckedChange = { attrs[fld.name] = if (it) "Sim" else "Não" })
            }
            FieldType.CHOICE -> {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("${fld.name}: ${v.ifEmpty { "(escolher)" }}") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (o in fld.options) DropdownMenuItem(text = { Text(o) }, onClick = { attrs[fld.name] = o; open = false })
                    }
                }
            }
        }
        Gap(4)
    }
}

@Composable
fun CategoryField(vm: MainViewModel, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cats = remember { vm.categories() }
    Box {
        OutlinedTextField(value, onChange, label = { Text("Categoria") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (cats.isNotEmpty()) TextButton(onClick = { open = true }) { Text("▼") } })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in cats) DropdownMenuItem(text = { Text(c) }, onClick = { onChange(c); open = false })
        }
    }
}

@Composable
fun PhotoEditor(vm: MainViewModel, photos: MutableList<String>, onExifCoord: ((Coord) -> Unit)?) {
    val ctx = LocalContext.current
    var camFile by remember { mutableStateOf<java.io.File?>(null) }
    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile
        if (ok && f != null) vm.takeCameraPhoto(f) { name, _ -> photos.add(name) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addPhoto(uri) { name, c ->
            photos.add(name)
            if (c != null && onExifCoord != null) onExifCoord(c)
        }
    }
    Text("Fotos", style = MaterialTheme.typography.labelLarge)
    ScrollRow {
        for (p in photos.toList()) {
            Box {
                PhotoThumb(vm.app.media.photoFile(p), 64) { vm.dialog.value?.let { } }
                IconButton(onClick = { photos.remove(p) }, modifier = Modifier.size(22.dp).align(Alignment.TopEnd).background(Color.White.copy(alpha = 0.8f), CircleShape)) {
                    Icon(Icons.Default.Close, "Remover", modifier = Modifier.size(14.dp))
                }
            }
        }
        OutlinedButton(onClick = {
            val f = vm.cameraPhotoFile()
            camFile = f
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            runCatching { takePic.launch(uri) }.onFailure { vm.toast("Câmera indisponível") }
        }) { Text("Câmera") }
        OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }) { Text("Galeria") }
    }
    if (onExifCoord != null) Text("Fotos da galeria com GPS podem definir a posição do ponto", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
}

@Composable
fun DialogHost(vm: MainViewModel, d: Dialog) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val close = { vm.dialog.value = null }
    when (d) {
        is Dialog.Waypoint -> WaypointDialog(vm, d.feature, settings)
        is Dialog.FeatureProps -> FeaturePropsDialog(vm, d.feature, settings)
        is Dialog.Import -> ImportDialog(vm, d)
        is Dialog.Export -> ExportDialog(vm, d, settings)
        is Dialog.Merge -> MergeDialog(vm, d.baseId)
        is Dialog.Reverse -> {
            var mirror by remember { mutableStateOf(true) }
            AlertDialog(onDismissRequest = close, title = { Text("Inverter sentido") }, text = {
                Column {
                    Text("A ordem dos vértices será invertida (útil para navegar no sentido oposto ao gravado).")
                    Gap()
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mirror, { mirror = true }); Text("Recalcular horários (mantém os intervalos)") }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!mirror, { mirror = false }); Text("Descartar horários") }
                }
            }, confirmButton = { Button(onClick = { vm.reverse(d.featureId, if (mirror) TrackOps.TimeMode.MIRROR else TrackOps.TimeMode.DROP); close() }) { Text("Inverter") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.Simplify -> {
            val f = vm.features.value.firstOrNull { it.id == d.featureId }
            var tol by remember { mutableStateOf(3f) }
            val before = f?.geom?.allCoords()?.size ?: 0
            val after = remember(tol) { f?.let { TrackOps.simplifyGeom(it.geom, tol.toDouble()).allCoords().size } ?: 0 }
            AlertDialog(onDismissRequest = close, title = { Text("Simplificar (Douglas-Peucker)") }, text = {
                Column {
                    Text("Tolerância: %.1f m".format(Locale.US, tol))
                    Slider(tol, { tol = it }, valueRange = 0.5f..50f)
                    Text("Vértices: $before → $after")
                }
            }, confirmButton = { Button(onClick = { vm.simplify(d.featureId, tol.toDouble()); close() }) { Text("Aplicar") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.FilterGps -> {
            val f = vm.features.value.firstOrNull { it.id == d.featureId }
            var acc by remember { mutableStateOf("30") }
            var spd by remember { mutableStateOf("150") }
            var jump by remember { mutableStateOf("") }
            val line = f?.geom as? Geom.Line
            val after = line?.parts?.sumOf { TrackOps.filterGps(it, CoordFormat.num(acc), CoordFormat.num(spd)?.div(3.6), CoordFormat.num(jump)).size } ?: 0
            AlertDialog(onDismissRequest = close, title = { Text("Filtrar pontos ruins do GPS") }, text = {
                Column {
                    NumField("Precisão máxima (m)", acc) { acc = it }
                    NumField("Velocidade máxima (km/h)", spd) { spd = it }
                    NumField("Salto máximo sem horário (m)", jump) { jump = it }
                    Gap()
                    Text("Pontos: ${line?.allCoords()?.size ?: 0} → $after")
                }
            }, confirmButton = { Button(onClick = { vm.filterGps(d.featureId, CoordFormat.num(acc), CoordFormat.num(spd), CoordFormat.num(jump)); close() }) { Text("Aplicar") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.Buffer -> {
            var dist by remember { mutableStateOf("10") }
            AlertDialog(onDismissRequest = close, title = { Text("Buffer") }, text = {
                Column {
                    Text("Cria uma área a uma distância fixa do elemento.")
                    NumField("Distância (m)", dist) { dist = it }
                }
            }, confirmButton = {
                Button(onClick = { CoordFormat.num(dist)?.takeIf { it > 0 }?.let { vm.buffer(d.featureId, it) }; close() }) { Text("Criar") }
            }, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.Stats -> StatsDialog(vm, d.featureId, settings)
        is Dialog.CoordInput -> {
            var c by remember { mutableStateOf<Coord?>(null) }
            val init = vm.currentCoord()
            FormDialog(
                if (d.purpose == "goto") "Ir para coordenada" else "Informar coordenada", close, "OK",
                onConfirm = {
                    val cc = c
                    if (cc != null) {
                        if (d.purpose == "goto") { vm.map.flyTo(cc.lat, cc.lon, 17.0); vm.goToCoord(cc, "Coordenada") } else vm.drawAddCoord(cc)
                        close()
                    }
                },
            ) { CoordEditor(init, settings) { c = it } }
        }
        is Dialog.NewProject -> {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = close, title = { Text("Novo projeto") }, text = {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
            }, confirmButton = { Button(onClick = { vm.createProject(name); close() }) { Text("Criar") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.RenameProject -> {
            val p = vm.projects.value.firstOrNull { it.id == d.projectId }
            var name by remember { mutableStateOf(p?.name ?: "") }
            var desc by remember { mutableStateOf(p?.description ?: "") }
            AlertDialog(onDismissRequest = close, title = { Text("Projeto") }, text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, label = { Text("Descrição") })
                }
            }, confirmButton = { Button(onClick = { p?.let { vm.updateProject(it.copy(name = name, description = desc)) }; close() }) { Text("Salvar") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.MergeProjects -> {
            val sel = remember { mutableStateListOf<Long>() }
            val others = vm.projects.value.filter { it.id != d.projectId }
            AlertDialog(onDismissRequest = close, title = { Text("Mesclar projetos") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Os elementos dos projetos marcados serão movidos para este projeto e os projetos de origem serão removidos. Uma versão é salva antes.")
                    for (p in others) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (p.id in sel) sel.remove(p.id) else sel.add(p.id) }) {
                        Checkbox(p.id in sel, { if (it) sel.add(p.id) else sel.remove(p.id) }); Text("${p.name} (${p.featureCount})")
                    }
                }
            }, confirmButton = { Button(onClick = { vm.mergeProjects(d.projectId, sel.toList()); close() }, enabled = sel.isNotEmpty()) { Text("Mesclar") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        }
        is Dialog.Confirm -> AlertDialog(onDismissRequest = close, title = { Text(d.title) }, text = { Text(d.text) },
            confirmButton = { Button(onClick = { d.action(); close() }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
        Dialog.FinishRecording -> FinishRecordingDialog(vm, settings)
        is Dialog.OfflineDownload -> OfflineDownloadDialog(vm, d, settings)
        Dialog.Converter -> ConverterDialog(vm, settings)
        Dialog.Battery -> BatteryDialog(vm)
        is Dialog.Photo -> {
            val f = vm.app.media.photoFile(d.path)
            val bmp = remember(d.path) { runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() }
            androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                    Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                        TextButton(onClick = { vm.app.startActivity(vm.shareIntent(f, "image/jpeg").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Compartilhar", color = Color.White) }
                        IconButton(onClick = close) { Icon(Icons.Default.Close, "Fechar", tint = Color.White) }
                    }
                }
            }
        }
        is Dialog.MoveToProject -> AlertDialog(onDismissRequest = close, title = { Text("Mover para o projeto") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (p in vm.projects.value) Text(p.name, Modifier.fillMaxWidth().clickable { vm.moveToProject(d.featureId, p.id); close() }.padding(12.dp))
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = close) { Text("Cancelar") } })
    }
}

@Composable
fun WaypointDialog(vm: MainViewModel, f0: GeoFeature, s: Settings) {
    val pt = (f0.geom as Geom.Pt).c
    var name by remember { mutableStateOf(f0.name) }
    var desc by remember { mutableStateOf(f0.description) }
    var cat by remember { mutableStateOf(f0.category) }
    var icon by remember { mutableStateOf(f0.style.icon) }
    var color by remember { mutableStateOf(f0.style.color) }
    var coord by remember { mutableStateOf<Coord?>(pt) }
    var ele by remember { mutableStateOf(pt.ele?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var editorKey by remember { mutableStateOf(0) }
    var editorInit by remember { mutableStateOf(pt) }
    val attrs = remember { mutableStateMapOf<String, String>().apply { putAll(f0.attrs) } }
    val photos = remember { mutableStateListOf<String>().apply { addAll(f0.photos) } }
    val project = vm.projects.value.firstOrNull { it.id == f0.projectId }
    FormDialog(
        if (f0.id == 0L) "Novo waypoint" else "Editar ponto", { vm.dialog.value = null },
        onConfirm = {
            val c = coord
            val missing = project?.form?.filter { it.required && attrs[it.name].isNullOrBlank() }?.map { it.name } ?: emptyList()
            when {
                c == null -> vm.toast("Coordenada inválida")
                missing.isNotEmpty() -> vm.toast("Preencha: ${missing.joinToString()}")
                else -> vm.saveFeature(f0.copy(
                    name = name.trim(), description = desc.trim(), category = cat.trim(),
                    geom = Geom.Pt(c.copy(ele = CoordFormat.num(ele), time = pt.time ?: System.currentTimeMillis())),
                    style = f0.style.copy(icon = icon, color = color), attrs = attrs.toMap(), photos = photos.toList(),
                ))
            }
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(desc, { desc = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        CategoryField(vm, cat) { cat = it }
        Gap()
        Text("Coordenada", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                val f = vm.fix.value
                if (f == null) vm.toast("Sem sinal de GPS") else {
                    editorInit = Coord(f.lat, f.lon, f.altitude); ele = f.altitude?.let { String.format(Locale.US, "%.2f", it) } ?: ele; editorKey++
                }
            }) { Text("GPS") }
            OutlinedButton(onClick = {
                val c = vm.map.center()
                val e = vm.app.dem.sample(c.lat, c.lon)
                editorInit = Coord(c.lat, c.lon, e); e?.let { ele = String.format(Locale.US, "%.2f", it) }; editorKey++
            }) { Text("Centro do mapa") }
        }
        androidx.compose.runtime.key(editorKey) { CoordEditor(editorInit, s) { coord = it } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumField("Altitude (m)", ele, Modifier.weight(1f)) { ele = it }
            TextButton(onClick = {
                val c = coord ?: return@TextButton
                val e = vm.app.dem.sample(c.lat, c.lon)
                if (e == null) vm.toast("MDE não baixado para esta área") else ele = String.format(Locale.US, "%.2f", e)
            }) { Text("Do MDE") }
        }
        Gap()
        IconPicker(vm, icon, color, { icon = it }, { color = it })
        if (!project?.form.isNullOrEmpty()) {
            Gap()
            Text("Atributos do projeto", style = MaterialTheme.typography.labelLarge)
            FormFields(project!!.form, attrs)
        }
        val extra = attrs.keys.filter { k -> project?.form?.none { it.name == k } != false }
        if (extra.isNotEmpty()) {
            Gap()
            Text("Outros atributos", style = MaterialTheme.typography.labelLarge)
            for (k in extra) OutlinedTextField(attrs[k] ?: "", { attrs[k] = it }, label = { Text(k) }, modifier = Modifier.fillMaxWidth())
        }
        AddAttribute(attrs)
        Gap()
        PhotoEditor(vm, photos) { c -> editorInit = c; c.ele?.let { ele = String.format(Locale.US, "%.2f", it) }; editorKey++ }
    }
}

@Composable
fun AddAttribute(attrs: MutableMap<String, String>) {
    var k by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(k, { k = it }, label = { Text("Novo atributo") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (k.isNotBlank() && !attrs.containsKey(k.trim())) attrs[k.trim()] = ""; k = "" }) { Text("Adicionar") }
    }
}

@Composable
fun FeaturePropsDialog(vm: MainViewModel, f0: GeoFeature, s: Settings) {
    var name by remember { mutableStateOf(f0.name) }
    var desc by remember { mutableStateOf(f0.description) }
    var cat by remember { mutableStateOf(f0.category) }
    var color by remember { mutableStateOf(f0.style.color) }
    var width by remember { mutableStateOf(f0.style.width) }
    var fill by remember { mutableStateOf(f0.style.fill or 0xFF000000.toInt()) }
    var fillAlpha by remember { mutableStateOf(((f0.style.fill ushr 24) and 0xFF) / 255f) }
    var eleSrc by remember { mutableStateOf(f0.eleSource ?: s.eleSourceDefault) }
    val attrs = remember { mutableStateMapOf<String, String>().apply { putAll(f0.attrs) } }
    val photos = remember { mutableStateListOf<String>().apply { addAll(f0.photos) } }
    val project = vm.projects.value.firstOrNull { it.id == f0.projectId }
    val poly = f0.type == GeomType.POLYGON
    FormDialog(
        if (f0.id == 0L) (if (poly) "Nova área" else "Nova trilha") else "Editar ${f0.type.label.lowercase()}", { vm.dialog.value = null },
        onConfirm = {
            val missing = project?.form?.filter { it.required && attrs[it.name].isNullOrBlank() }?.map { it.name } ?: emptyList()
            if (missing.isNotEmpty()) vm.toast("Preencha: ${missing.joinToString()}")
            else vm.saveFeature(f0.copy(
                name = name.trim(), description = desc.trim(), category = cat.trim(),
                style = f0.style.copy(color = color, width = width, fill = ((fillAlpha * 255).toInt() shl 24) or (fill and 0xFFFFFF)),
                attrs = attrs.toMap(), photos = photos.toList(), eleSource = if (f0.recorded) eleSrc else f0.eleSource,
            ))
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(desc, { desc = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        CategoryField(vm, cat) { cat = it }
        Gap()
        when (val g = f0.geom) {
            is Geom.Line -> Text("Comprimento: ${CoordFormat.distance(GeoMath.length(g), s.useKm)}")
            is Geom.Poly -> {
                Text("Área: ${CoordFormat.area(GeoMath.area(g, s.fixedZone, if (s.fixedZone != null) s.fixedSouth else null), s.useKm)}")
                Text("Perímetro: ${CoordFormat.distance(GeoMath.perimeter(g), s.useKm)}")
            }
            else -> {}
        }
        Gap()
        ColorPicker(if (poly) "Cor do contorno" else "Cor da linha", color) { color = it }
        Text("Espessura: %.1f".format(Locale.US, width))
        Slider(width, { width = it }, valueRange = 1f..12f)
        if (poly) {
            ColorPicker("Cor do preenchimento", fill) { fill = it }
            Text("Opacidade do preenchimento: ${(fillAlpha * 100).toInt()}%")
            Slider(fillAlpha, { fillAlpha = it }, valueRange = 0f..1f)
        }
        if (f0.recorded) {
            Gap()
            Text("Altitude da trilha gravada", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(eleSrc == "gps", { eleSrc = "gps" }); Text("GPS")
                Spacer(Modifier.width(12.dp))
                RadioButton(eleSrc == "dem", { eleSrc = "dem" }); Text("Modelo de elevação")
            }
        }
        if (!project?.form.isNullOrEmpty()) {
            Gap()
            Text("Atributos do projeto", style = MaterialTheme.typography.labelLarge)
            FormFields(project!!.form, attrs)
        }
        val extra = attrs.keys.filter { k -> project?.form?.none { it.name == k } != false }
        if (extra.isNotEmpty()) {
            Gap()
            Text("Outros atributos", style = MaterialTheme.typography.labelLarge)
            for (k in extra) OutlinedTextField(attrs[k] ?: "", { attrs[k] = it }, label = { Text(k) }, modifier = Modifier.fillMaxWidth())
        }
        AddAttribute(attrs)
        Gap()
        PhotoEditor(vm, photos, null)
    }
}

@Composable
fun ImportDialog(vm: MainViewModel, d: Dialog.Import) {
    val r = d.result
    val groups = remember(r) {
        r.features.withIndex().groupBy { (_, f) -> (if (f.folder.isNotBlank()) f.folder + " · " else "") + f.type.label + "s" }
    }
    val sel = remember(r) { mutableStateListOf<Int>().apply { addAll(r.features.indices) } }
    var toNew by remember { mutableStateOf(d.targetProject == null) }
    var target by remember { mutableStateOf(d.targetProject ?: vm.activeProjectId()) }
    var newName by remember { mutableStateOf(r.sourceName.substringBeforeLast('.')) }
    var projOpen by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    FormDialog(
        "Importar ${r.sourceName}", { vm.dialog.value = null }, "Importar (${sel.size})",
        onConfirm = {
            if (sel.isEmpty()) vm.toast("Selecione ao menos um elemento")
            else {
                vm.dialog.value = null
                vm.confirmImport(r, sel.sorted().map { r.features[it] }, if (toNew) null else target, newName)
            }
        },
    ) {
        Text("${r.features.size} elemento(s) encontrados: " + GeomType.entries.joinToString(" · ") { t -> "${r.features.count { it.type == t }} ${t.label.lowercase()}(s)" },
            style = MaterialTheme.typography.bodySmall)
        if (r.warnings.isNotEmpty()) {
            Gap(4)
            for (w in r.warnings.take(6)) Text("⚠ $w", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100))
        }
        Gap(6)
        Row {
            TextButton(onClick = { sel.clear(); sel.addAll(r.features.indices) }) { Text("Marcar todos") }
            TextButton(onClick = { sel.clear() }) { Text("Desmarcar todos") }
        }
        for ((g, items) in groups) {
            val idx = items.map { it.index }
            val all = idx.all { it in sel }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded[g] = !(expanded[g] ?: false) }) {
                Checkbox(all, { c -> if (c) idx.forEach { if (it !in sel) sel.add(it) } else sel.removeAll(idx) })
                Text("$g (${idx.count { it in sel }}/${idx.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (expanded[g] == true) "▲" else "▼")
            }
            if (expanded[g] == true) {
                for ((i, f) in items.take(500)) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
                    Checkbox(i in sel, { c -> if (c) sel.add(i) else sel.remove(i) })
                    Text(f.name.ifBlank { "(sem nome)" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Destino", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(toNew, { toNew = true }); Text("Criar projeto novo") }
        if (toNew) OutlinedTextField(newName, { newName = it }, label = { Text("Nome do projeto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!toNew, { toNew = false }); Text("Adicionar a um projeto existente") }
        if (!toNew) Box {
            OutlinedButton(onClick = { projOpen = true }) { Text(vm.projects.value.firstOrNull { it.id == target }?.name ?: "Escolher projeto") }
            DropdownMenu(expanded = projOpen, onDismissRequest = { projOpen = false }) {
                for (p in vm.projects.value) DropdownMenuItem(text = { Text(p.name) }, onClick = { target = p.id; projOpen = false })
            }
        }
        Text("Estilos (ícones, cores e espessuras) do KML/KMZ são preservados.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun ExportDialog(vm: MainViewModel, d: Dialog.Export, s: Settings) {
    var fmt by remember { mutableStateOf(ExportFormat.KMZ) }
    FormDialog("Exportar: ${d.title}", { vm.dialog.value = null }, onConfirm = null, extraButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.export(d.projectId, d.featureIds, fmt, false, d.title); vm.dialog.value = null }) { Text("Salvar") }
            Button(onClick = { vm.export(d.projectId, d.featureIds, fmt, true, d.title); vm.dialog.value = null }) { Text("Compartilhar") }
        }
    }) {
        for (f in ExportFormat.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { fmt = f }) {
            RadioButton(fmt == f, { fmt = f }); Text(f.label)
        }
        Gap()
        val note = when (fmt) {
            ExportFormat.KMZ, ExportFormat.KML -> "Abre no Google Earth e no Google My Maps (web). O app Google Maps não importa KML."
            ExportFormat.SHP -> "Um shapefile por tipo de geometria, datum ${s.datum.label}, codificação UTF-8 (.cpg)."
            ExportFormat.DXF -> "Coordenadas UTM ${s.fixedZone?.let { "fuso $it${if (s.fixedSouth) "S" else "N"}" } ?: "(fuso do centro dos dados)"}, ${s.datum.label}."
            ExportFormat.CSV -> "Somente pontos; separador ponto e vírgula; inclui UTM."
            ExportFormat.GPX -> "Áreas são exportadas como trilhas fechadas."
            ExportFormat.GPKG -> "GeoPackage com tabelas de pontos, trilhas e áreas (EPSG:4326)."
            ExportFormat.GEOJSON -> "GeoJSON com estilos no padrão simplestyle."
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text("Em \"Salvar\" o seletor do Android permite escolher o Google Drive ou outra nuvem.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun MergeDialog(vm: MainViewModel, baseId: Long) {
    val lines = vm.features.value.filter { it.type == GeomType.LINE }
    val order = remember { mutableStateListOf<Long>().apply { add(baseId) } }
    val rev = remember { mutableStateMapOf<Long, Boolean>() }
    var connect by remember { mutableStateOf(true) }
    var keep by remember { mutableStateOf(false) }
    FormDialog("Mesclar trilhas", { vm.dialog.value = null }, "Mesclar", onConfirm = {
        vm.merge(baseId, order.map { it to (rev[it] == true) }, connect, keep)
    }) {
        Text("Ordem da fusão", style = MaterialTheme.typography.labelLarge)
        order.forEachIndexed { i, id ->
            val f = lines.firstOrNull { it.id == id } ?: return@forEachIndexed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${f.name}", Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { rev[id] = !(rev[id] ?: false) }) { Icon(Icons.Default.SwapVert, "Inverter", tint = if (rev[id] == true) MaterialTheme.colorScheme.secondary else Color.Gray) }
                IconButton(onClick = { if (i > 0) { order.removeAt(i); order.add(i - 1, id) } }) { Icon(Icons.Default.ArrowUpward, "Subir") }
                IconButton(onClick = { if (i < order.size - 1) { order.removeAt(i); order.add(i + 1, id) } }) { Icon(Icons.Default.ArrowDownward, "Descer") }
            }
        }
        TextButton(onClick = {
            val ls = order.mapNotNull { id -> (lines.firstOrNull { it.id == id }?.geom as? Geom.Line)?.allCoords() }
            if (ls.size == order.size) TrackOps.autoOrient(ls).forEachIndexed { i, r -> rev[order[i]] = r }
        }) { Text("Orientar automaticamente (pontas mais próximas)") }
        HorizontalDivider()
        Text("Adicionar trilhas", style = MaterialTheme.typography.labelLarge)
        for (f in lines.filter { it.id !in order }) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { order.add(f.id) }) {
            Checkbox(false, { order.add(f.id) }); Text(f.name, maxLines = 1)
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(connect, { connect = it }); Spacer(Modifier.width(8.dp)); Text("Unir num segmento contínuo") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(keep, { keep = it }); Spacer(Modifier.width(8.dp)); Text("Manter trilhas originais") }
    }
}

@Composable
fun StatsDialog(vm: MainViewModel, id: Long, s: Settings) {
    val f = vm.features.value.firstOrNull { it.id == id } ?: return
    val g = f.geom as? Geom.Line ?: return
    val useDem = f.eleSource == "dem"
    val st = remember(f) {
        val eles = if (useDem) g.allCoords().map { vm.app.dem.sample(it.lat, it.lon) } else null
        Stats.track(g.parts, eles, if (useDem) 2.0 else 4.0)
    }
    FormDialog("Estatísticas: ${f.name}", { vm.dialog.value = null }, onConfirm = null) {
        @Composable
        fun row(k: String, v: String?) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(k, Modifier.weight(1f)); Text(v ?: "—", fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
        row("Comprimento", CoordFormat.distance(st.length, s.useKm))
        row("Pontos", st.points.toString())
        row("Subida acumulada", "%.0f m".format(Locale.US, st.ascent))
        row("Descida acumulada", "%.0f m".format(Locale.US, st.descent))
        row("Altitude mínima", st.minEle?.let { "%.1f m".format(Locale.US, it) })
        row("Altitude máxima", st.maxEle?.let { "%.1f m".format(Locale.US, it) })
        row("Duração total", st.duration?.let { CoordFormat.duration(it) })
        row("Tempo em movimento", st.movingTime?.let { CoordFormat.duration(it) })
        row("Tempo parado", st.stoppedTime?.let { CoordFormat.duration(it) })
        row("Velocidade média", st.avgSpeed?.let { "%.1f km/h".format(Locale.US, it * 3.6) })
        row("Média em movimento", st.avgMovingSpeed?.let { "%.1f km/h".format(Locale.US, it * 3.6) })
        row("Velocidade máxima", st.maxSpeed?.let { "%.1f km/h".format(Locale.US, it * 3.6) })
        Text("Altitude: ${if (useDem) "modelo de elevação" else "GPS"}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun FinishRecordingDialog(vm: MainViewModel, s: Settings) {
    var name by remember { mutableStateOf(vm.defaultTrackName()) }
    var desc by remember { mutableStateOf("") }
    var ele by remember { mutableStateOf(s.eleSourceDefault) }
    var project by remember { mutableStateOf(vm.activeProjectId()) }
    var open by remember { mutableStateOf(false) }
    val rec = vm.recorder.state.value
    FormDialog("Concluir gravação", { vm.dialog.value = null }, "Salvar", onConfirm = {
        vm.finishRecording(name.trim().ifEmpty { vm.defaultTrackName() }, desc.trim(), ele, project)
    }, extraButton = {
        TextButton(onClick = {
            vm.dialog.value = Dialog.Confirm("Descartar", "Descartar a trilha gravada?") { vm.discardRecording() }
        }) { Text("Descartar", color = MaterialTheme.colorScheme.error) }
    }) {
        Text("${CoordFormat.distance(rec.distance, s.useKm)} · ${CoordFormat.duration(rec.elapsedNow())} · ${rec.points} pontos")
        Gap()
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(desc, { desc = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Gap()
        Box {
            OutlinedButton(onClick = { open = true }) { Text("Projeto: " + (vm.projects.value.firstOrNull { it.id == project }?.name ?: "—")) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (p in vm.projects.value) DropdownMenuItem(text = { Text(p.name) }, onClick = { project = p.id; open = false })
            }
        }
        Gap()
        Text("Altitude usada no perfil", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(ele == "gps", { ele = "gps" }); Text("GPS")
            Spacer(Modifier.width(12.dp))
            RadioButton(ele == "dem", { ele = "dem" }); Text("Modelo de elevação")
        }
        Text("Pode ser trocada depois no perfil de elevação.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Gap()
        TextButton(onClick = { vm.dialog.value = null; vm.resumeRecording() }) { Text("Continuar gravando") }
    }
}

@Composable
fun OfflineDownloadDialog(vm: MainViewModel, d: Dialog.OfflineDownload, s: Settings) {
    val b = GeoMath.bounds(d.ring)!!
    val bases = vm.allBases().filter { it.offline }
    var name by remember { mutableStateOf(if (d.purpose == "dem") "MDE " + java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(System.currentTimeMillis()) else "Área " + java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(System.currentTimeMillis())) }
    var base by remember { mutableStateOf(bases.firstOrNull { it.id == s.baseMap }?.id ?: bases.firstOrNull()?.id ?: "osm_vec") }
    var minZ by remember { mutableStateOf(10f) }
    var maxZ by remember { mutableStateOf(16f) }
    var dem by remember { mutableStateOf(s.demSource) }
    var open by remember { mutableStateOf(false) }
    val ring = if (d.free) d.ring else null
    val tiles = remember(minZ.toInt(), maxZ.toInt()) { TileMath.count(b, minZ.toInt(), maxZ.toInt(), ring) }
    val isVector = bases.firstOrNull { it.id == base }?.styleUrl != null
    val mb = tiles * vm.offline.avgTileBytes(if (isVector) "vector" else "raster") / 1e6
    val used = vm.offline.totalBytes() / 1e6
    FormDialog(if (d.purpose == "dem") "Baixar modelo de elevação" else "Baixar mapa offline", { vm.dialog.value = null }, "Baixar", onConfirm = {
        if (d.purpose != "dem" && tiles > 150_000) vm.toast("Área muito grande ($tiles tiles). Reduza a área ou o zoom máximo.")
        else vm.startOfflineDownload(d.purpose, name, d.ring, d.free, base, minZ.toInt(), maxZ.toInt(), dem)
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap()
        val w = GeoMath.distance(b.center.lat, b.minLon, b.center.lat, b.maxLon)
        val h = GeoMath.distance(b.minLat, b.center.lon, b.maxLat, b.center.lon)
        Text("Extensão aproximada: ${CoordFormat.distance(w, true)} × ${CoordFormat.distance(h, true)}", style = MaterialTheme.typography.bodySmall)
        if (d.purpose == "dem") {
            Gap()
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(dem == "copernicus", { dem = "copernicus" }); Text("Copernicus GLO-30 (recomendado)") }
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(dem == "srtm", { dem = "srtm" }); Text("SRTM 1\" (30 m)") }
            val n = vm.app.dem.tilesFor(b).size
            Text("$n folha(s) de 1°×1° · ~${n * (if (dem == "srtm") 25 else 30)} MB", style = MaterialTheme.typography.bodySmall)
        } else {
            Gap()
            Box {
                OutlinedButton(onClick = { open = true }) { Text("Fonte: " + (bases.firstOrNull { it.id == base }?.name ?: base)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (bb in bases) DropdownMenuItem(text = { Text(bb.name) }, onClick = { base = bb.id; open = false })
                }
            }
            if (base == "esri" && s.arcgisKey.isBlank()) Text("Requer a chave ArcGIS (Configurações) — serviço oficial de exportação.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            Text("Zoom mínimo: ${minZ.toInt()}")
            Slider(minZ, { minZ = it.coerceAtMost(maxZ) }, valueRange = 0f..19f, steps = 18)
            Text("Zoom máximo: ${maxZ.toInt()}")
            Slider(maxZ, { maxZ = it.coerceAtLeast(minZ) }, valueRange = 0f..19f, steps = 18)
            Text("$tiles tiles · estimativa ~${"%.0f".format(Locale.US, mb)} MB", fontWeight = FontWeight.SemiBold,
                color = if (tiles > 150_000) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        Text("Uso atual: ${"%.0f".format(Locale.US, used)} MB de ${s.cacheLimitMb} MB. Ao passar do limite, as áreas mais antigas são apagadas.",
            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun ConverterDialog(vm: MainViewModel, s: Settings) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { CoordFormat.parse(text, s.coordPrefs) }
    FormDialog("Conversor de coordenadas", { vm.dialog.value = null }, onConfirm = null) {
        OutlinedTextField(text, { text = it }, label = { Text("Coordenada (qualquer formato)") }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("-5.79, -35.21  ou  24S 257000 9359000  ou  5°47'S 35°12'W") })
        Row {
            TextButton(onClick = { vm.fix.value?.let { text = CoordFormat.dd(it.lat, it.lon) } }) { Text("GPS") }
            TextButton(onClick = { vm.map.center().let { text = CoordFormat.dd(it.lat, it.lon) } }) { Text("Centro do mapa") }
        }
        if (parsed == null) {
            if (text.isNotBlank()) Text("Formato não reconhecido", color = MaterialTheme.colorScheme.error)
        } else {
            val (lat, lon) = parsed
            val u = CoordFormat.toUtm(lat, lon, s.coordPrefs)
            val auto = Utm.fromLatLon(lat, lon, e = s.datum.ellipsoid)
            val rows = listOf(
                "Graus decimais" to CoordFormat.dd(lat, lon),
                "GMS" to CoordFormat.dms(lat, lon),
                "UTM (${s.datum.label})" to String.format(Locale.US, "%d%s %.3f %.3f", u.zone, u.hemisphere, u.e, u.n),
            ) + (if (u.zone != auto.zone) listOf("UTM fuso natural" to String.format(Locale.US, "%d%s %.3f %.3f", auto.zone, auto.hemisphere, auto.e, auto.n)) else emptyList()) +
                listOfNotNull(Utm.sirgasEpsg(u.zone, u.south)?.let { "EPSG" to "$it (SIRGAS 2000 / UTM ${u.zone}${u.hemisphere})" })
            for ((k, v) in rows) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(v, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = { copyToClipboard(ctx, k, v); vm.toast("Copiado") }) { Icon(Icons.Default.ContentCopy, "Copiar") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.dialog.value = null; vm.map.flyTo(lat, lon, 17.0) }) { Text("Ir para") }
                Button(onClick = { vm.newWaypoint(Coord(lat, lon, vm.app.dem.sample(lat, lon))) }) { Text("Criar ponto") }
            }
        }
    }
}

@Composable
fun BatteryDialog(vm: MainViewModel) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    FormDialog("Funcionamento em segundo plano", { vm.dialog.value = null }, onConfirm = null) {
        Text("Para gravar trilhas com a tela desligada, o GeoMaps usa um serviço em primeiro plano (notificação fixa) e precisa ficar fora da economia de bateria.")
        Gap()
        Text(if (ignoring) "✅ O GeoMaps já está liberado da otimização de bateria." else "⚠ O GeoMaps ainda está sujeito à otimização de bateria.",
            fontWeight = FontWeight.SemiBold, color = if (ignoring) Color(0xFF2E7D32) else Color(0xFFE65100))
        Gap()
        Button(onClick = {
            runCatching {
                ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }.onFailure { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, enabled = !ignoring) { Text("Ignorar otimização de bateria") }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }) { Text("Abrir configurações do app") }
        Gap()
        Text("Xiaomi, Samsung, Motorola e outros têm economias extras. Verifique também:", fontWeight = FontWeight.SemiBold)
        Text("• Xiaomi/Redmi: Bateria → Sem restrições; Inicialização automática ativada.\n• Samsung: Bateria → Sem restrição; remover dos \"apps em suspensão\".\n• Motorola/outros: Bateria → Otimização → Não otimizar.\n• Mantenha o GeoMaps fixado nos apps recentes (cadeado).",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com"))) } }) { Text("Guia por fabricante (dontkillmyapp.com)") }
    }
}

@Suppress("unused")
private val unusedBorder = BorderStroke(1.dp, Color.Gray)
