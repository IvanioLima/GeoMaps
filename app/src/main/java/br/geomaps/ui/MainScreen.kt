package br.geomaps.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.geomaps.core.GeomType
import br.geomaps.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    val cam by vm.map.camera.collectAsStateWithLifecycle()
    val follow by vm.follow.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val rec by vm.recorder.state.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val sats by vm.hub.satellites.collectAsStateWithLifecycle()
    val gpsStatus by vm.hub.status.collectAsStateWithLifecycle()
    val active by vm.activeProject.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val exportReq by vm.exportRequest.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)

    var importTarget by remember { mutableStateOf<Long?>(null) }
    var showBases by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri, importTarget)
        importTarget = null
    }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri != null && data != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) } }.isSuccess }
                vm.toast(if (ok) "Arquivo salvo" else "Erro ao salvar o arquivo")
            }
        }
    }
    val backupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.io("Gerando backup…") {
            ctx.contentResolver.openOutputStream(uri)?.use { Backup.write(vm.repo, it) }
            vm.toast("Backup salvo")
        }
    }

    // Exportação: salvar (seletor do Android, inclui Google Drive) ou compartilhar
    LaunchedEffect(exportReq) {
        val r = exportReq ?: return@LaunchedEffect
        vm.exportRequest.value = null
        if (r.share) {
            val f = withContext(Dispatchers.IO) {
                File(ctx.cacheDir, "share").apply { mkdirs() }.let { d -> File(d, r.fileName).also { it.writeBytes(r.bytes) } }
            }
            ctx.startActivity(vm.shareIntent(f, r.mime))
        } else {
            pendingExport = r.bytes
            createFile.launch(r.fileName)
        }
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        Toast.makeText(ctx, m, if (m.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        vm.message.value = null
    }

    BackHandler(enabled = drawer.isOpen || screen != Screen.NONE || mode != Mode.Browse || selected != null || profile != null) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            screen != Screen.NONE -> vm.screen.value = Screen.NONE
            mode != Mode.Browse -> vm.cancelMode()
            profile != null -> vm.closeProfile()
            else -> vm.selectedId.value = null
        }
    }

    fun openImport(target: Long?) {
        importTarget = target
        openFile.launch(arrayOf("*/*"))
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("GeoMaps", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 4.dp))
                    Text("Projeto ativo: ${active?.name ?: "—"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                    Gap(12)
                    @Composable
                    fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) =
                        NavigationDrawerItem(icon = { Icon(icon, null) }, label = { Text(label) }, selected = false,
                            onClick = { scope.launch { drawer.close() }; action() }, modifier = Modifier.padding(horizontal = 12.dp))
                    item(Icons.Default.Folder, "Meus Mapas (projetos)") { vm.screen.value = Screen.PROJECTS }
                    item(Icons.AutoMirrored.Filled.List, "Lista e busca de elementos") { vm.screen.value = Screen.LIST }
                    item(Icons.Default.FolderOpen, "Importar arquivo") { openImport(null) }
                    item(Icons.Default.CloudDownload, "Mapas offline e MDE") { vm.screen.value = Screen.OFFLINE }
                    item(Icons.Default.Wallpaper, "Camadas personalizadas (WMS/XYZ)") { vm.screen.value = Screen.SOURCES }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    item(Icons.Default.Straighten, "Medir distância e área") { vm.startMeasure() }
                    item(Icons.Default.Brush, "Desenho à mão livre") { vm.selectedId.value = null; vm.mode.value = Mode.Freehand }
                    item(Icons.Default.SwapHoriz, "Conversor de coordenadas") { vm.dialog.value = Dialog.Converter }
                    item(Icons.Default.Bluetooth, "GNSS externo (Bluetooth)") { vm.screen.value = Screen.GNSS }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    item(Icons.Default.Backup, "Fazer backup (Drive/arquivo)") {
                        backupFile.launch("geomaps_backup_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(System.currentTimeMillis()) + ".geomaps")
                    }
                    item(Icons.Default.Restore, "Restaurar backup") { restoreFile.launch(arrayOf("*/*")) }
                    item(Icons.Default.Settings, "Configurações") { vm.screen.value = Screen.SETTINGS }
                    item(Icons.Default.BatteryChargingFull, "Segundo plano e bateria") { vm.dialog.value = Dialog.Battery }
                    item(Icons.Default.Info, "Sobre") { vm.screen.value = Screen.ABOUT }
                    Gap(16)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            MapViewHost(vm)

            val crosshair = mode is Mode.Draw || mode is Mode.EditVertices || mode is Mode.Measure || mode is Mode.PickArea
            if (crosshair) Crosshair(Modifier.align(Alignment.Center))

            if (mode == Mode.Freehand) FreehandOverlay(vm)

            // ---------------------------------------------------------- topo
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
                TopBar(
                    vm = vm, projectName = active?.name ?: "—", projects = projects.map { it.id to it.name },
                    canUndo = canUndo, canRedo = canRedo,
                    onMenu = { scope.launch { drawer.open() } },
                    onImport = { openImport(null) },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    CompassWidget(cam.bearing) {
                        if (vm.follow.value == FollowMode.HEADING) vm.follow.value = FollowMode.FOLLOW
                        vm.map.setBearing(0.0)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        CoordBox(fix, cam, settings) { vm.dialog.value = Dialog.Converter }
                        Gap(4)
                        ScaleBar(cam, settings.useKm)
                        if (settings.telemetry) {
                            Gap(6)
                            TelemetryPanel(fix, sats, gpsStatus, heading, settings)
                        }
                    }
                }
                nav?.let { NavPanel(vm, it, fix, heading, settings) }
            }

            // ---------------------------------------------------------- base
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    if (mode == Mode.Browse) PlusButton(vm, rec.active)
                    Spacer(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                        RoundButton(Icons.Default.MyLocation, "Ir para minha posição") {
                            val f = vm.fix.value
                            if (f == null) vm.toast(gpsStatus) else vm.map.flyTo(f.lat, f.lon, maxOf(vm.map.camera.value.zoom, 16.0))
                        }
                        RoundButton(
                            if (follow == FollowMode.HEADING) Icons.Default.Navigation else Icons.Default.NearMe,
                            "Seguir posição", active = follow != FollowMode.OFF,
                        ) {
                            vm.follow.value = when (follow) {
                                FollowMode.OFF -> FollowMode.FOLLOW
                                FollowMode.FOLLOW -> FollowMode.HEADING
                                FollowMode.HEADING -> { vm.map.setBearing(0.0); FollowMode.OFF }
                            }
                            vm.toast(when (vm.follow.value) {
                                FollowMode.FOLLOW -> "Seguindo a posição"
                                FollowMode.HEADING -> "Seguindo com rotação pelo rumo"
                                FollowMode.OFF -> "Seguir desativado"
                            })
                            vm.fix.value?.let { f -> vm.map.follow(f.lat, f.lon, if (vm.follow.value == FollowMode.HEADING) heading?.toDouble() else null) }
                        }
                        RoundButton(Icons.Default.Layers, "Mapa base") { showBases = true }
                    }
                }
                when (val m = mode) {
                    is Mode.Draw -> DrawBar(vm, m, settings)
                    is Mode.EditVertices -> EditBar(vm, m)
                    is Mode.Measure -> MeasureBar(vm, m, settings)
                    is Mode.Split -> SplitBar(vm, m)
                    is Mode.PickArea -> PickAreaBar(vm, m)
                    else -> {}
                }
                if (rec.active) RecordingPanel(vm, rec, settings)
                if (mode == Mode.Browse) {
                    val p = profile
                    if (p != null) ProfilePanel(vm, p, settings)
                    else selected?.let { FeatureSheet(vm, it, settings) }
                }
            }

            busy?.let {
                Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(it)
                    }
                }
            }

            when (screen) {
                Screen.NONE -> {}
                Screen.PROJECTS -> ProjectsScreen(vm, onImport = { openImport(it) })
                Screen.LIST -> FeatureListScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.OFFLINE -> OfflineScreen(vm)
                Screen.SOURCES -> SourcesScreen(vm)
                Screen.GNSS -> GnssScreen(vm)
                Screen.VERSIONS -> VersionsScreen(vm)
                Screen.FORM -> FormScreen(vm)
                Screen.ABOUT -> AboutScreen(vm)
            }
        }
    }

    if (showBases) BaseMapSheet(vm, settings) { showBases = false }
    dialog?.let { DialogHost(vm, it) }
}

@Composable
fun MapViewHost(vm: MainViewModel) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { m ->
                vm.map.attach(m)
                vm.applyBase()
            }
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun TopBar(
    vm: MainViewModel, projectName: String, projects: List<Pair<Long, String>>, canUndo: Boolean, canRedo: Boolean,
    onMenu: () -> Unit, onImport: () -> Unit,
) {
    var projMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f), shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu", tint = Color.White) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { projMenu = true }) {
                    Text(projectName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                    Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                }
                DropdownMenu(expanded = projMenu, onDismissRequest = { projMenu = false }) {
                    Text("Projeto ativo (novos elementos)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    for ((id, name) in projects) {
                        DropdownMenuItem(text = { Text(name) }, onClick = { vm.setActive(id); projMenu = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Novo projeto…") }, onClick = { projMenu = false; vm.dialog.value = Dialog.NewProject() })
                    DropdownMenuItem(text = { Text("Gerenciar projetos") }, onClick = { projMenu = false; vm.screen.value = Screen.PROJECTS })
                }
            }
            IconButton(onClick = { vm.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer", tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.35f)) }
            IconButton(onClick = { vm.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Refazer", tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.35f)) }
            IconButton(onClick = { vm.screen.value = Screen.LIST }) { Icon(Icons.Default.Search, "Buscar", tint = Color.White) }
            IconButton(onClick = { vm.screen.value = Screen.PROJECTS }) { Icon(Icons.Default.Visibility, "Projetos visíveis", tint = Color.White) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Mais", tint = Color.White) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Importar arquivo") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { more = false; onImport() })
                    DropdownMenuItem(text = { Text("Medir") }, leadingIcon = { Icon(Icons.Default.Straighten, null) }, onClick = { more = false; vm.startMeasure() })
                    DropdownMenuItem(text = { Text("Mão livre") }, leadingIcon = { Icon(Icons.Default.Brush, null) }, onClick = { more = false; vm.selectedId.value = null; vm.mode.value = Mode.Freehand })
                    DropdownMenuItem(text = { Text(if (settings.telemetry) "Ocultar telemetria" else "Mostrar telemetria") }, leadingIcon = { Icon(Icons.Default.Timeline, null) },
                        onClick = { more = false; vm.app.settings.update { it.copy(telemetry = !it.telemetry) } })
                    DropdownMenuItem(text = { Text(if (settings.labels) "Ocultar nomes dos pontos" else "Mostrar nomes dos pontos") }, leadingIcon = { Icon(Icons.Default.Place, null) },
                        onClick = { more = false; vm.app.settings.update { it.copy(labels = !it.labels) } })
                    DropdownMenuItem(text = { Text("Exportar projeto ativo") }, leadingIcon = { Icon(Icons.Default.Backup, null) },
                        onClick = { more = false; vm.activeProject.value?.let { vm.dialog.value = Dialog.Export(it.id, null, it.name) } })
                    DropdownMenuItem(text = { Text("Conversor de coordenadas") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = { more = false; vm.dialog.value = Dialog.Converter })
                }
            }
        }
    }
}

@Composable
private fun PlusButton(vm: MainViewModel, recording: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { open = true }, containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color.White) {
            Icon(Icons.Default.Add, "Adicionar", modifier = Modifier.size(30.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Criar waypoint") }, leadingIcon = { Icon(Icons.Default.Place, null) }, onClick = { open = false; vm.newWaypoint() })
            DropdownMenuItem(text = { Text("Criar trilha (manual)") }, leadingIcon = { Icon(Icons.Default.Route, null) }, onClick = { open = false; vm.startDraw(GeomType.LINE) })
            DropdownMenuItem(
                text = { Text(if (recording) "Gravação em andamento" else "Gravar trilha com o GPS") },
                leadingIcon = { Icon(Icons.Default.RadioButtonChecked, null, tint = Color(0xFFD32F2F)) },
                enabled = !recording, onClick = { open = false; vm.startRecording() },
            )
            DropdownMenuItem(text = { Text("Adicionar área (polígono)") }, leadingIcon = { Icon(Icons.Default.CropSquare, null) }, onClick = { open = false; vm.startDraw(GeomType.POLYGON) })
            DropdownMenuItem(text = { Text("Medir distância/área") }, leadingIcon = { Icon(Icons.Default.SquareFoot, null) }, onClick = { open = false; vm.startMeasure() })
        }
    }
}

/** Desenho à mão livre sobre o mapa (exporta como imagem ou salva como linhas). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreehandOverlay(vm: MainViewModel) {
    val ctx = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val color by vm.freehandColor.collectAsStateWithLifecycle()
    val colors = listOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { current = listOf(it) },
                    onDrag = { ch, _ -> current = current + ch.position },
                    onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                )
            },
        ) {
            val st = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (s in strokes + listOf(current)) {
                if (s.size < 2) continue
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(p, Color(color), style = st)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp), shape = MaterialTheme.shapes.large, shadowElevation = 6.dp) {
            Column(Modifier.padding(10.dp)) {
                Text("Desenho à mão livre", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    for (c in colors) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape, color = Color(c),
                            border = androidx.compose.foundation.BorderStroke(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.secondary else Color.Gray),
                            modifier = Modifier.size(30.dp), onClick = { vm.freehandColor.value = c },
                        ) {}
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }) { Text("Desfazer") }
                    TextButton(onClick = { strokes.clear() }) { Text("Limpar") }
                    TextButton(onClick = {
                        vm.saveFreehandAsLines(strokes.map { s -> s.map { it.x to it.y } }, color)
                    }) { Text("Salvar linhas") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val snapStrokes = strokes.toList()
                        vm.map.snapshot { bmp ->
                            val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
                            val cv = android.graphics.Canvas(out)
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                this.color = color; style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 4 * ctx.resources.displayMetrics.density; strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                            for (s in snapStrokes) {
                                if (s.size < 2) continue
                                val p = android.graphics.Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                                cv.drawPath(p, paint)
                            }
                            saveAndShareImage(vm, out)
                        }
                    }) { Text("Exportar imagem") }
                    TextButton(onClick = { vm.cancelMode() }) { Text("Fechar") }
                }
            }
        }
    }
}

private fun saveAndShareImage(vm: MainViewModel, bmp: Bitmap) {
    val ctx = vm.app
    val name = "geomaps_esboco_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".png"
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoMaps")
            }
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { uri ->
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, name)
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    vm.toast("Imagem salva em Imagens/GeoMaps")
    ctx.startActivity(vm.shareIntent(f, "image/png").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
