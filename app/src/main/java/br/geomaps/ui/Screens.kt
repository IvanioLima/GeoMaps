package br.geomaps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.geomaps.core.CoordFormat
import br.geomaps.core.CoordSystem
import br.geomaps.core.DatumChoice
import br.geomaps.core.FieldType
import br.geomaps.core.FormField
import br.geomaps.core.GeoMath
import br.geomaps.core.Geom
import br.geomaps.core.GeomType
import br.geomaps.data.Db
import br.geomaps.data.GpsProfile
import br.geomaps.data.Settings
import br.geomaps.location.BluetoothGnss
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun FullScreen(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Surface(color = MaterialTheme.colorScheme.primary, shadowElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    actions()
                }
            }
            content()
        }
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

// ====================================================================== Meus Mapas

@Composable
fun ProjectsScreen(vm: MainViewModel, onImport: (Long?) -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    FullScreen("Meus Mapas", { vm.screen.value = Screen.NONE }, actions = {
        TextButton(onClick = { onImport(null) }) { Text("Importar", color = Color.White) }
        IconButton(onClick = { vm.dialog.value = Dialog.NewProject() }) { Icon(Icons.Default.Add, "Novo", tint = Color.White) }
    }) {
        Text("Marque os projetos visíveis (podem ser sobrepostos). O projeto ativo recebe os novos elementos.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(projects, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { vm.setActive(p.id) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.visible, { vm.setVisible(p.id, it) })
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.id == settings.activeProjectId) Text("  ATIVO", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Text("${p.featureCount} elemento(s) · atualizado em " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(p.updated),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opções") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            @Composable
                            fun mi(t: String, a: () -> Unit) = DropdownMenuItem(text = { Text(t) }, onClick = { menu = false; a() })
                            mi("Tornar ativo") { vm.setActive(p.id) }
                            mi("Ver no mapa") { if (!p.visible) vm.setVisible(p.id, true); vm.screen.value = Screen.NONE; vm.zoomToProject(p.id) }
                            mi("Renomear / descrição") { vm.dialog.value = Dialog.RenameProject(p.id) }
                            mi("Importar arquivo para este projeto") { onImport(p.id) }
                            mi("Exportar / compartilhar") { vm.dialog.value = Dialog.Export(p.id, null, p.name) }
                            mi("Duplicar") { vm.duplicateProject(p.id) }
                            mi("Mesclar com outros projetos") { vm.dialog.value = Dialog.MergeProjects(p.id) }
                            mi("Formulário de atributos") { vm.screenArg.value = p.id; vm.screen.value = Screen.FORM }
                            mi("Histórico de versões") { vm.screenArg.value = p.id; vm.screen.value = Screen.VERSIONS }
                            mi("Excluir") {
                                vm.dialog.value = Dialog.Confirm("Excluir projeto", "Excluir \"${p.name}\" e seus ${p.featureCount} elementos? Esta ação não pode ser desfeita.") { vm.deleteProject(p.id) }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

// ====================================================================== Lista e busca

@Composable
fun FeatureListScreen(vm: MainViewModel) {
    val features by vm.features.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<GeomType?>(GeomType.POINT) }
    var byDistance by remember { mutableStateOf(false) }
    var onlyActive by remember { mutableStateOf(false) }
    val active = vm.activeProjectId()
    val list = remember(features, q, type, byDistance, onlyActive, fix?.lat) {
        val ql = q.trim().lowercase()
        features.filter { f ->
            (type == null || f.type == type) && (!onlyActive || f.projectId == active) &&
                (ql.isEmpty() || f.name.lowercase().contains(ql) || f.category.lowercase().contains(ql) || f.description.lowercase().contains(ql) ||
                    f.attrs.values.any { it.lowercase().contains(ql) })
        }.let { l ->
            val fx = fix
            if (byDistance && fx != null) l.sortedBy { f -> val c = GeoMath.centroid(f.geom.allCoords()); GeoMath.haversine(fx.lat, fx.lon, c.lat, c.lon) }
            else l.sortedBy { it.name.lowercase() }
        }
    }
    FullScreen("Elementos (${list.size})", { vm.screen.value = Screen.NONE }) {
        OutlinedTextField(q, { q = it }, label = { Text("Buscar por nome, categoria, descrição ou atributo") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        ScrollRow {
            Spacer(Modifier.width(6.dp))
            FilterChip(type == null, { type = null }, label = { Text("Todos") })
            FilterChip(type == GeomType.POINT, { type = GeomType.POINT }, label = { Text("Pontos") })
            FilterChip(type == GeomType.LINE, { type = GeomType.LINE }, label = { Text("Trilhas") })
            FilterChip(type == GeomType.POLYGON, { type = GeomType.POLYGON }, label = { Text("Áreas") })
            FilterChip(byDistance, { byDistance = !byDistance }, label = { Text("Mais próximos") })
            FilterChip(onlyActive, { onlyActive = !onlyActive }, label = { Text("Só projeto ativo") })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().clickable { vm.select(f.id) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (f.type) { GeomType.POINT -> Icons.Default.Place; GeomType.LINE -> Icons.Default.Route; GeomType.POLYGON -> Icons.Default.CropSquare }
                    Icon(icon, null, tint = Color(f.style.color or 0xFF000000.toInt()))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.ifBlank { "(sem nome)" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val proj = projects.firstOrNull { it.id == f.projectId }?.name ?: ""
                        val extra = when (val g = f.geom) {
                            is Geom.Pt -> CoordFormat.format(g.c.lat, g.c.lon, settings.coordPrefs)
                            is Geom.Line -> CoordFormat.distance(GeoMath.length(g), settings.useKm)
                            is Geom.Poly -> CoordFormat.area(GeoMath.area(g), settings.useKm)
                        }
                        Text(listOf(f.category, proj, extra).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    fix?.let { fx ->
                        val c = GeoMath.centroid(f.geom.allCoords())
                        Text(CoordFormat.distance(GeoMath.haversine(fx.lat, fx.lon, c.lat, c.lon), settings.useKm), style = MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

// ====================================================================== Configurações

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val st = vm.app.settings
    FullScreen("Configurações", { vm.screen.value = Screen.NONE }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Atualização do GPS")
            for (p in GpsProfile.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { st.update { it.copy(gpsProfile = p) } }) {
                RadioButton(s.gpsProfile == p, { st.update { it.copy(gpsProfile = p) } }); Text(p.label + if (p == GpsProfile.HIGH) " — padrão" else "")
            }
            if (s.gpsProfile == GpsProfile.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Intervalo (s)", s.customIntervalSec.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { n -> st.update { it.copy(customIntervalSec = n.coerceIn(1, 3600)) } } }
                    NumField("Distância mínima (m)", s.customDistanceM.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { n -> st.update { it.copy(customDistanceM = n.coerceIn(0, 10000)) } } }
                }
            }
            Text("Precisão máxima aceita na gravação: ${s.recMaxAccuracyM} m")
            Slider(s.recMaxAccuracyM.toFloat(), { v -> st.update { it.copy(recMaxAccuracyM = v.toInt()) } }, valueRange = 5f..200f)

            SectionTitle("Unidades")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!s.useKm, { st.update { it.copy(useKm = false) } }); Text("Metro")
                Spacer(Modifier.width(16.dp))
                RadioButton(s.useKm, { st.update { it.copy(useKm = true) } }); Text("Quilômetro (acima de 1000 m)")
            }

            SectionTitle("Coordenadas")
            for (c in CoordSystem.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { st.update { it.copy(coordSystem = c) } }) {
                RadioButton(s.coordSystem == c, { st.update { it.copy(coordSystem = c) } }); Text(c.label)
            }
            Text("Datum", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            for (d in DatumChoice.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { st.update { it.copy(datum = d) } }) {
                RadioButton(s.datum == d, { st.update { it.copy(datum = d) } }); Text(d.label)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.fixedZone != null, { on ->
                    val z = vm.fix.value?.let { br.geomaps.core.Utm.zoneFor(it.lat, it.lon) } ?: vm.map.center().let { br.geomaps.core.Utm.zoneFor(it.lat, it.lon) }
                    st.update { it.copy(fixedZone = if (on) z else null) }
                })
                Spacer(Modifier.width(8.dp))
                Text("Fuso UTM fixo (todo o levantamento no mesmo fuso)")
            }
            if (s.fixedZone != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField("Fuso", s.fixedZone.toString(), Modifier.width(100.dp)) { v -> v.toIntOrNull()?.takeIf { it in 1..60 }?.let { n -> st.update { it.copy(fixedZone = n) } } }
                FilterChip(s.fixedSouth, { st.update { it.copy(fixedSouth = true) } }, label = { Text("Sul") })
                FilterChip(!s.fixedSouth, { st.update { it.copy(fixedSouth = false) } }, label = { Text("Norte") })
            }
            Text("No Rio Grande do Norte: fusos 24S e 25S. EPSG SIRGAS 2000: 31984 (24S) e 31985 (25S).", style = MaterialTheme.typography.labelSmall, color = Color.Gray)

            SectionTitle("Altitude e modelo de elevação")
            Text("Fonte padrão para trilhas gravadas:")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.eleSourceDefault == "gps", { st.update { it.copy(eleSourceDefault = "gps") } }); Text("GPS")
                Spacer(Modifier.width(16.dp))
                RadioButton(s.eleSourceDefault == "dem", { st.update { it.copy(eleSourceDefault = "dem") } }); Text("Modelo de elevação")
            }
            Text("MDE preferido:")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.demSource == "copernicus", { st.update { it.copy(demSource = "copernicus") } }); Text("Copernicus GLO-30")
                Spacer(Modifier.width(16.dp))
                RadioButton(s.demSource == "srtm", { st.update { it.copy(demSource = "srtm") } }); Text("SRTM 1\"")
            }

            SectionTitle("Mapas e cache")
            Text("Limite do cache offline: ${s.cacheLimitMb} MB")
            Slider(s.cacheLimitMb.toFloat(), { v -> st.update { it.copy(cacheLimitMb = (v / 256).toInt() * 256) } }, valueRange = 256f..8192f)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.labels, { v -> st.update { it.copy(labels = v) } }); Spacer(Modifier.width(8.dp)); Text("Mostrar nomes dos pontos") }
            OutlinedTextField(s.googleKey, { v -> st.update { it.copy(googleKey = v) } }, label = { Text("Chave Google Map Tiles API (opcional)") }, placeholder = { Text("Usando a chave padrão do GeoMaps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Crie em console.cloud.google.com (ative \"Map Tiles API\"). Uso somente online; há cota gratuita mensal.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            OutlinedTextField(s.arcgisKey, { v -> st.update { it.copy(arcgisKey = v) } }, label = { Text("Chave ArcGIS (API key)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Necessária para baixar o Esri World Imagery offline pelo serviço oficial de exportação (conta gratuita de desenvolvedor ArcGIS, com privilégio de basemaps offline).",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)

            SectionTitle("Edição")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.snapping, { v -> st.update { it.copy(snapping = v) } }); Spacer(Modifier.width(8.dp)); Text("Encaixar em vértices existentes (snapping)") }
            Text("Tolerância do snapping: ${s.snapPx} px")
            Slider(s.snapPx.toFloat(), { v -> st.update { it.copy(snapPx = v.toInt()) } }, valueRange = 8f..60f)

            SectionTitle("Navegação")
            Text("Alerta de desvio ao seguir trilha: ${s.deviationAlertM} m")
            Slider(s.deviationAlertM.toFloat(), { v -> st.update { it.copy(deviationAlertM = v.toInt()) } }, valueRange = 5f..200f)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(s.keepScreenOn, { v -> st.update { it.copy(keepScreenOn = v) } }); Spacer(Modifier.width(8.dp)); Text("Manter a tela ligada com o app aberto") }

            SectionTitle("Dados")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.autoBackup, { v -> st.update { it.copy(autoBackup = v) }; br.geomaps.data.Backup.schedule(vm.app, v) })
                Spacer(Modifier.width(8.dp))
                Text("Backup automático diário (mantém os 7 últimos)")
            }
            Text("Pasta: Android/data/br.geomaps.app/files/backups. Para guardar no Google Drive use Menu → Fazer backup.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.screen.value = Screen.GNSS }) { Text("GNSS externo") }
            }
            Gap(24)
        }
    }
}

// ====================================================================== Offline

@Composable
fun OfflineScreen(vm: MainViewModel) {
    val areas by vm.offline.areas.collectAsStateWithLifecycle()
    val progress by vm.offline.progress.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val total = areas.sumOf { it.size }
    FullScreen("Mapas offline e MDE", { vm.screen.value = Screen.NONE }) {
        Column(Modifier.padding(12.dp)) {
            Text("Ocupado: ${"%.1f".format(Locale.US, total / 1e6)} MB de ${s.cacheLimitMb} MB")
            LinearProgressIndicator(progress = { (total / (s.cacheLimitMb * 1048576.0)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text("Novo download de mapa", fontWeight = FontWeight.SemiBold)
            ScrollRow {
                OutlinedButton(onClick = { vm.startPickArea("map", false) }) { Text("Retângulo") }
                OutlinedButton(onClick = { vm.startPickArea("map", true) }) { Text("Área livre") }
                OutlinedButton(onClick = { vm.pickVisibleArea("map") }) { Text("Tela atual") }
            }
            Text("Novo download do modelo de elevação", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            ScrollRow {
                OutlinedButton(onClick = { vm.startPickArea("dem", false) }) { Text("Retângulo") }
                OutlinedButton(onClick = { vm.pickVisibleArea("dem") }) { Text("Tela atual") }
            }
            TextButton(onClick = { vm.offline.clearAmbientCache { vm.toast(it) } }) { Text("Limpar cache de navegação online") }
            Text("Google: somente online. Esri: download oficial com chave ArcGIS. OSM: offline pelo mapa vetorial. Ao passar do limite, as áreas mais antigas são apagadas.",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(areas, key = { it.id }) { a ->
                val p = progress[a.id]
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold)
                        val kind = when (a.kind) { "dem" -> "MDE ${if (a.source == "srtm") "SRTM" else "Copernicus"}"; "vector" -> "OSM vetorial"; else -> vm.allBases().firstOrNull { it.id == a.source }?.name ?: a.source }
                        Text("$kind · ${if (a.kind != "dem") "zoom ${a.minZoom}–${a.maxZoom} · " else ""}${"%.1f".format(Locale.US, a.size / 1e6)} MB · ${a.status}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(a.created), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        if (p != null) {
                            LinearProgressIndicator(progress = { if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            Text("${p.done}/${p.total}" + (if (p.failed > 0) " · ${p.failed} falhas" else "") + if (p.message.isNotEmpty()) " · ${p.message}" else "",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(onClick = { vm.map.fit(listOf(br.geomaps.core.Coord(a.minLat, a.minLon), br.geomaps.core.Coord(a.maxLat, a.maxLon))); vm.screen.value = Screen.NONE }) { Text("Ver") }
                    IconButton(onClick = {
                        vm.dialog.value = Dialog.Confirm("Excluir área offline", "Apagar \"${a.name}\"?") { vm.offline.delete(a) { vm.applyBase() } }
                    }) { Icon(Icons.Default.Delete, "Excluir") }
                }
                HorizontalDivider()
            }
        }
    }
}

// ====================================================================== Fontes personalizadas

@Composable
fun SourcesScreen(vm: MainViewModel) {
    val sources by vm.customSources.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Db.CustomSource?>(null) }
    FullScreen("Camadas personalizadas", { vm.screen.value = Screen.NONE }, actions = {
        IconButton(onClick = { editing = Db.CustomSource(name = "", type = "xyz", url = "") }) { Icon(Icons.Default.Add, "Nova", tint = Color.White) }
    }) {
        Text("Adicione servidores XYZ, WMS ou WMTS (ex.: INDE, IBGE, SIGEF/INCRA ou servidores próprios). Camadas marcadas como sobreposição aparecem por cima da base.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        LazyColumn {
            items(sources, key = { it.id }) { src ->
                Row(Modifier.fillMaxWidth().clickable { editing = src }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(src.name, fontWeight = FontWeight.SemiBold)
                        Text("${src.type.uppercase()} · ${if (src.overlay) "sobreposição" else "base"}${if (src.allowOffline) " · offline" else ""}", style = MaterialTheme.typography.bodySmall)
                        Text(src.url, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.deleteCustomSource(src.id) }) { Icon(Icons.Default.Delete, "Excluir") }
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { e ->
        var name by remember(e) { mutableStateOf(e.name) }
        var type by remember(e) { mutableStateOf(e.type) }
        var url by remember(e) { mutableStateOf(e.url) }
        var layers by remember(e) { mutableStateOf(e.layers) }
        var overlay by remember(e) { mutableStateOf(e.overlay) }
        var opacity by remember(e) { mutableStateOf(e.opacity) }
        var maxZ by remember(e) { mutableStateOf(e.maxZoom.toString()) }
        var offline by remember(e) { mutableStateOf(e.allowOffline) }
        FormDialog(if (e.id == 0L) "Nova camada" else "Editar camada", { editing = null }, onConfirm = {
            if (name.isBlank() || url.isBlank()) vm.toast("Informe nome e URL")
            else {
                vm.saveCustomSource(e.copy(name = name.trim(), type = type, url = url.trim(), layers = layers.trim(), overlay = overlay, opacity = opacity,
                    maxZoom = maxZ.toIntOrNull()?.coerceIn(1, 22) ?: 20, allowOffline = offline))
                editing = null
            }
        }) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                for (t in listOf("xyz", "wms", "wmts")) Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(type == t, { type = t }); Text(t.uppercase()) }
            }
            OutlinedTextField(url, { url = it }, label = { Text(if (type == "wms") "URL base do WMS" else "Modelo de URL") }, modifier = Modifier.fillMaxWidth())
            Text(when (type) {
                "wms" -> "Ex.: https://servidor/geoserver/wms — o app monta GetMap em EPSG:3857."
                "wmts" -> "Use {TileMatrix}, {TileCol} e {TileRow} (ou {z}/{x}/{y}) no modelo REST, com matriz GoogleMapsCompatible/EPSG:3857."
                else -> "Ex.: https://servidor/tiles/{z}/{x}/{y}.png — também aceita {s}, {-y} e {quadkey}."
            }, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            if (type == "wms") OutlinedTextField(layers, { layers = it }, label = { Text("Camadas (LAYERS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            NumField("Zoom máximo", maxZ) { maxZ = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(overlay, { overlay = it }); Spacer(Modifier.width(8.dp)); Text("Sobreposição (por cima da base)") }
            if (overlay) { Text("Opacidade: ${(opacity * 100).toInt()}%"); Slider(opacity, { opacity = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(offline, { offline = it }); Spacer(Modifier.width(8.dp)); Text("Permitir download offline") }
            Text("Ative o download somente se os termos do servidor permitirem.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

// ====================================================================== GNSS externo

@Composable
fun GnssScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.hub.status.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val hasPerm = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) devices = BluetoothGnss.paired(ctx) }
    LaunchedEffect(hasPerm) { if (hasPerm) devices = BluetoothGnss.paired(ctx) }
    FullScreen("GNSS externo (Bluetooth)", { vm.screen.value = Screen.NONE }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Use receptores GNSS de precisão ou RTK que enviam NMEA 0183 por Bluetooth (perfil serial). Pareie o receptor nas configurações do Android antes.",
                style = MaterialTheme.typography.bodySmall)
            Gap()
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.gnssSource == "internal", { vm.app.settings.update { it.copy(gnssSource = "internal") } }); Text("GPS interno do aparelho")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.gnssSource == "bt", {
                    if (s.btAddress.isEmpty()) vm.toast("Escolha um receptor na lista") else vm.app.settings.update { it.copy(gnssSource = "bt") }
                }); Text("Receptor externo: ${s.btName.ifEmpty { "nenhum" }}")
            }
            Gap()
            Text("Status: $status", fontWeight = FontWeight.SemiBold)
            fix?.let { Text("Fonte: ${it.source}${it.quality?.let { q -> " · $q" } ?: ""} · precisão ${it.accuracy?.let { a -> "±%.2f m".format(Locale.US, a) } ?: "—"}") }
            SectionTitle("Receptores pareados")
            if (!hasPerm) Button(onClick = { perm.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Permitir Bluetooth") }
            if (hasPerm && devices.isEmpty()) Text("Nenhum dispositivo pareado.")
            for ((name, addr) in devices) Row(Modifier.fillMaxWidth().clickable {
                vm.app.settings.update { it.copy(btAddress = addr, btName = name, gnssSource = "bt") }
                vm.toast("Usando $name")
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.btAddress == addr, null)
                Column { Text(name); Text(addr, style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
            }
            TextButton(onClick = { if (hasPerm) devices = BluetoothGnss.paired(ctx) }) { Text("Atualizar lista") }
        }
    }
}

// ====================================================================== Versões

@Composable
fun VersionsScreen(vm: MainViewModel) {
    val pid by vm.screenArg.collectAsStateWithLifecycle()
    var versions by remember { mutableStateOf(emptyList<Db.Version>()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(pid, refresh) {
        versions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.app.db.versions(pid) }
    }
    val name = vm.projects.value.firstOrNull { it.id == pid }?.name ?: ""
    FullScreen("Versões: $name", { vm.screen.value = Screen.PROJECTS }, actions = {
        TextButton(onClick = { vm.snapshot(pid, "Ponto de restauração manual"); refresh++ }) { Text("Salvar agora", color = Color.White) }
    }) {
        Text("Pontos de restauração do projeto (mantidos os 30 mais recentes). Restaurar substitui os elementos atuais — uma cópia do estado atual é salva antes.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        LazyColumn {
            items(versions, key = { it.id }) { v ->
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.label, fontWeight = FontWeight.SemiBold)
                        Text(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(v.created) + " · ${v.count} elemento(s)", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        vm.dialog.value = Dialog.Confirm("Restaurar versão", "Restaurar \"${v.label}\"?") { vm.restoreVersion(pid, v.id); refresh++ }
                    }) { Text("Restaurar") }
                    IconButton(onClick = { vm.io { vm.app.db.deleteVersion(v.id) }; refresh++ }) { Icon(Icons.Default.Delete, "Excluir") }
                }
                HorizontalDivider()
            }
        }
    }
}

// ====================================================================== Formulário de atributos

@Composable
fun FormScreen(vm: MainViewModel) {
    val pid by vm.screenArg.collectAsStateWithLifecycle()
    val project = vm.projects.value.firstOrNull { it.id == pid } ?: return
    val fields = remember(pid) { mutableStateListOf<FormField>().apply { addAll(project.form) } }
    var editIdx by remember { mutableStateOf<Int?>(null) }
    FullScreen("Formulário: ${project.name}", { vm.screen.value = Screen.PROJECTS }, actions = {
        TextButton(onClick = { vm.updateProject(project.copy(form = fields.toList())); vm.toast("Formulário salvo"); vm.screen.value = Screen.PROJECTS }) { Text("Salvar", color = Color.White) }
    }) {
        Text("Campos preenchidos ao criar ou editar elementos deste projeto.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(fields.size) { i ->
                val f = fields[i]
                Row(Modifier.fillMaxWidth().clickable { editIdx = i }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name + if (f.required) " *" else "", fontWeight = FontWeight.SemiBold)
                        Text(f.type.label + if (f.type == FieldType.CHOICE) ": " + f.options.joinToString(", ") else "", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { fields.removeAt(i) }) { Icon(Icons.Default.Delete, "Remover") }
                }
                HorizontalDivider()
            }
        }
        Button(onClick = { fields.add(FormField("Campo ${fields.size + 1}")); editIdx = fields.size - 1 }, modifier = Modifier.padding(12.dp)) { Text("Adicionar campo") }
    }
    editIdx?.let { i ->
        val f = fields.getOrNull(i) ?: return@let
        var name by remember(i) { mutableStateOf(f.name) }
        var type by remember(i) { mutableStateOf(f.type) }
        var opts by remember(i) { mutableStateOf(f.options.joinToString("\n")) }
        var req by remember(i) { mutableStateOf(f.required) }
        FormDialog("Campo", { editIdx = null }, "OK", onConfirm = {
            fields[i] = FormField(name.trim().ifEmpty { "Campo" }, type, opts.lines().map { it.trim() }.filter { it.isNotEmpty() }, req)
            editIdx = null
        }) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome do campo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            for (t in FieldType.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { type = t }) { RadioButton(type == t, { type = t }); Text(t.label) }
            if (type == FieldType.CHOICE) OutlinedTextField(opts, { opts = it }, label = { Text("Opções (uma por linha)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(req, { req = it }); Text("Obrigatório") }
        }
    }
}

// ====================================================================== Sobre

@Composable
fun AboutScreen(vm: MainViewModel) {
    FullScreen("Sobre o GeoMaps", { vm.screen.value = Screen.NONE }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("GeoMaps ${br.geomaps.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("Gestão de dados espaciais e edição avançada de KML, KMZ, GPX e Shapefile.")
            SectionTitle("Fontes de dados")
            Text("• OpenStreetMap © colaboradores do OSM (ODbL)\n• OpenFreeMap / OpenMapTiles (mapa vetorial)\n• OpenTopoMap (CC-BY-SA)\n• Esri World Imagery © Esri e fornecedores\n• Google Map Tiles API © Google (somente online)\n• Copernicus DEM GLO-30 © DLR/Airbus, ESA\n• SRTM (NASA/USGS) via AWS Terrain Tiles",
                style = MaterialTheme.typography.bodySmall)
            SectionTitle("Bibliotecas")
            Text("MapLibre Native (BSD), JTS Topology Suite (EPL/EDL), AndroidX e Jetpack Compose (Apache 2.0).", style = MaterialTheme.typography.bodySmall)
            SectionTitle("Dicas")
            Text("• Toque longo no mapa cria um ponto ali.\n• Na edição de vértices, toque num vértice e depois no mapa para movê-lo; círculos brancos inserem vértices.\n• Toque num atributo do ponto para copiá-lo.\n• Toque na coordenada (canto superior direito) para abrir o conversor.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(1.dp))
        }
    }
}
