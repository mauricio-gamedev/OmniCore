package com.omnicore.emulator.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnicore.emulator.BuildConfig
import com.omnicore.emulator.achievements.OmniAchievements
import com.omnicore.emulator.core.CoreRegistry
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.library.LibraryImportEngine
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.settings.Ps1Settings
import com.omnicore.emulator.storage.GameLibraryStore
import com.omnicore.emulator.storage.Ps1Files
import com.omnicore.emulator.ui.achievements.AchievementsScreen
import com.omnicore.emulator.ui.n64.N64SettingsDialog
import com.omnicore.emulator.ui.ps2.PS2SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OmniHubScreen { LIBRARY, SYSTEMS, ACHIEVEMENTS, SETTINGS }
private enum class OmniSort { RECENT, TITLE, SIZE }

// Lean Hub uses opaque, single-pass surfaces. Alpha 5 feedback showed that
// stacked gradients/translucent cards were too expensive during scroll on
// lower-end Android GPUs even after the animated starfield was removed.
private val OmniBg = Color(0xFF070914)
private val OmniPanel = Color(0xFF171A2E)
private val OmniPanelStrong = Color(0xFF1C1C35)
private val OmniTextSoft = Color(0xFFB2B7CE)
private val OmniAccent = Color(0xFFA58BFF)
private val OmniAccent2 = Color(0xFF74DFFF)
private val OmniBadge = Color(0xFF293253)
private val OmniHeroPanel = Color(0xFF15253A)

/** System-neutral OmniCore shell. Console-specific state remains isolated. */
@Composable
fun OmniCoreV4App() {
    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(OmniHubScreen.LIBRARY) }
    var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var selectedSystem by remember { mutableStateOf<ConsoleSystem?>(null) }
    var importSystem by remember { mutableStateOf<ConsoleSystem?>(null) }
    var importDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var biosCount by remember { mutableIntStateOf(0) }
    var showN64Settings by remember { mutableStateOf(false) }
    var showPS2Settings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val startup = withContext(Dispatchers.IO) {
            val loadedGames = store.load()
            runCatching { OmniAchievements.setCounter(context, "library_games", loadedGames.size) }
            loadedGames to Ps1Files.biosFiles(context).size
        }
        games = startup.first
        biosCount = startup.second
    }

    fun saveSnapshot(next: List<GameEntry>) {
        games = next
        scope.launch(Dispatchers.IO) {
            runCatching {
                store.save(next)
                OmniAchievements.setCounter(context, "library_games", next.size)
            }
        }
    }

    fun applyReport(report: LibraryImportEngine.Report) {
        importing = false
        if (report.games.isNotEmpty()) {
            val merged = (games + report.games).distinctBy { it.uri }
            saveSnapshot(merged)
        }
        message = buildString {
            append(report.summary)
            if (report.warnings.isNotEmpty() && report.games.isNotEmpty()) {
                append("\n\n")
                append(report.warnings.take(2).joinToString("\n"))
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { LibraryImportEngine.importFiles(context, uris, importSystem) }
                    .getOrElse {
                        LibraryImportEngine.Report(
                            games = emptyList(),
                            scanned = uris.size,
                            skipped = uris.size,
                            warnings = listOf(it.message ?: "Falha ao analisar os arquivos selecionados.")
                        )
                    }
            }
            applyReport(report)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        importing = true
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { LibraryImportEngine.importFolder(context, treeUri, importSystem) }
                    .getOrElse {
                        LibraryImportEngine.Report(
                            games = emptyList(),
                            scanned = 0,
                            skipped = 0,
                            warnings = listOf(it.message ?: "Falha ao analisar a pasta selecionada.")
                        )
                    }
            }
            applyReport(report)
        }
    }

    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { Ps1Files.importBios(context, uri) }
            result.onSuccess {
                biosCount = withContext(Dispatchers.IO) { Ps1Files.biosFiles(context).size }
                message = "BIOS PS1 importada: ${it.name}"
            }.onFailure {
                message = it.message ?: "Não consegui importar essa BIOS."
            }
        }
    }

    Box(Modifier.fillMaxSize().background(OmniBg)) {
        Scaffold(
            containerColor = OmniBg,
            topBar = {
                OmniTopBar(
                    gameCount = games.size,
                    importing = importing,
                    onImport = { importDialog = true }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0C0E15), tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = screen == OmniHubScreen.LIBRARY,
                        onClick = { screen = OmniHubScreen.LIBRARY },
                        icon = { Text("▦") },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = screen == OmniHubScreen.SYSTEMS,
                        onClick = { screen = OmniHubScreen.SYSTEMS },
                        icon = { Text("◉") },
                        label = { Text("Sistemas") }
                    )
                    NavigationBarItem(
                        selected = screen == OmniHubScreen.ACHIEVEMENTS,
                        onClick = { screen = OmniHubScreen.ACHIEVEMENTS },
                        icon = { Text("★") },
                        label = { Text("Conquistas") }
                    )
                    NavigationBarItem(
                        selected = screen == OmniHubScreen.SETTINGS,
                        onClick = { screen = OmniHubScreen.SETTINGS },
                        icon = { Text("⚙") },
                        label = { Text("Ajustes") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    OmniHubScreen.LIBRARY -> OmniLibrary(
                        games = games,
                        selectedSystem = selectedSystem,
                        importing = importing,
                        onSystem = { selectedSystem = it },
                        onImport = {
                            // The library filter doubles as a sensible initial import target,
                            // but the dialog can now override it explicitly.
                            importSystem = selectedSystem
                            importDialog = true
                        },
                        onPlay = { game ->
                            val core = CoreRegistry.forSystem(game.system)
                            when {
                                core == null || core.info.state == CoreState.PLANNED ->
                                    message = "${game.system.displayName} já pode ficar na biblioteca, mas o core ainda está planejado."
                                game.system == ConsoleSystem.PLAYSTATION_1 && !core.isAvailable() ->
                                    message = "O core PS1 não está presente neste APK."
                                else -> core.launch(context, game).exceptionOrNull()?.let {
                                    message = it.message ?: "Falha ao iniciar ${game.title}."
                                }
                            }
                        },
                        onRemove = { game -> saveSnapshot(games.filterNot { it.id == game.id }) }
                    )
                    OmniHubScreen.SYSTEMS -> OmniSystems(
                        games = games,
                        onN64Settings = { showN64Settings = true },
                        onPS2Settings = { showPS2Settings = true }
                    )
                    OmniHubScreen.ACHIEVEMENTS -> AchievementsScreen()
                    OmniHubScreen.SETTINGS -> OmniSettings(
                        biosCount = biosCount,
                        onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        onN64Settings = { showN64Settings = true },
                        onPS2Settings = { showPS2Settings = true }
                    )
                }
            }
        }
    }

    if (importDialog) {
        AlertDialog(
            onDismissRequest = { importDialog = false },
            containerColor = OmniPanelStrong,
            title = { Text("Adicionar jogos", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Escolha o sistema de destino para formatos compartilhados. “Auto” mantém a detecção automática dos formatos exclusivos.",
                        color = OmniTextSoft
                    )
                    Text("Importar como", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = importSystem == null,
                                onClick = { importSystem = null },
                                label = { Text("Auto") }
                            )
                        }
                        items(ConsoleSystem.entries) { system ->
                            FilterChip(
                                selected = importSystem == system,
                                onClick = { importSystem = system },
                                label = { Text(system.shortName) }
                            )
                        }
                    }
                    Text(
                        if (importSystem == ConsoleSystem.PSP) {
                            "PSP selecionado: ISO/CSO/PBP serão importados como PSP; a seleção de pasta também procura jogos em subpastas."
                        } else {
                            "Para jogos de PSP, selecione PSP aqui antes de escolher os arquivos ou a pasta. Isso evita confundir imagens de consoles diferentes."
                        },
                        color = OmniTextSoft,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            importDialog = false
                            folderPicker.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Selecionar pasta de jogos") }
                    Button(
                        onClick = {
                            importDialog = false
                            filePicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30364B))
                    ) { Text("Selecionar arquivos") }
                    Text(
                        "N64: Z64/N64/V64/ZIP/GZIP • PS1: CUE/BIN • PS2: ISO validada ou seleção manual • PSP: ISO/CSO/PBP com seleção PSP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7F879D)
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Fechar") } }
        )
    }

    if (showN64Settings) N64SettingsDialog(onDismiss = { showN64Settings = false })
    if (showPS2Settings) PS2SettingsDialog(onDismiss = { showPS2Settings = false })

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = OmniPanelStrong,
            title = { Text("OmniCore") },
            text = { Text(text, color = OmniTextSoft) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun OmniTopBar(gameCount: Int, importing: Boolean, onImport: () -> Unit) {
    Surface(color = Color(0xFF0B0D14), tonalElevation = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(OmniAccent),
                contentAlignment = Alignment.Center
            ) {
                Text("★", color = Color(0xFFFFD85A), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text("OmniCore", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (importing) "Analisando biblioteca…" else "Multi-system • v${BuildConfig.VERSION_NAME} • $gameCount jogo(s)",
                    color = OmniTextSoft,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Button(onClick = onImport, enabled = !importing) {
                Text(if (importing) "…" else "+ Jogos")
            }
        }
    }
}

@Composable
private fun OmniLibrary(
    games: List<GameEntry>,
    selectedSystem: ConsoleSystem?,
    importing: Boolean,
    onSystem: (ConsoleSystem?) -> Unit,
    onImport: () -> Unit,
    onPlay: (GameEntry) -> Unit,
    onRemove: (GameEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(OmniSort.RECENT) }
    var pendingRemoval by remember { mutableStateOf<GameEntry?>(null) }

    val shown = remember(games, selectedSystem, query, sort) {
        val filtered = games.asSequence()
            .filter { selectedSystem == null || it.system == selectedSystem }
            .filter {
                query.isBlank() || it.title.contains(query.trim(), true) || it.fileName.contains(query.trim(), true)
            }
            .toList()
        when (sort) {
            OmniSort.RECENT -> filtered.sortedByDescending { it.addedAt }
            OmniSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            OmniSort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { OmniHero(games) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Buscar jogos") },
                placeholder = { Text("Nome ou arquivo") }
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedSystem == null,
                        onClick = { onSystem(null) },
                        label = { Text("Todos") }
                    )
                }
                items(ConsoleSystem.entries) { system ->
                    FilterChip(
                        selected = selectedSystem == system,
                        onClick = { onSystem(system) },
                        label = { Text(system.shortName) }
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(sort == OmniSort.RECENT, { sort = OmniSort.RECENT }, label = { Text("Recentes") }) }
                item { FilterChip(sort == OmniSort.TITLE, { sort = OmniSort.TITLE }, label = { Text("A–Z") }) }
                item { FilterChip(sort == OmniSort.SIZE, { sort = OmniSort.SIZE }, label = { Text("Tamanho") }) }
            }
        }

        if (shown.isEmpty()) {
            item {
                OmniCard {
                    Text(
                        if (games.isEmpty()) "Biblioteca unificada" else "Nenhum jogo nesse filtro",
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (games.isEmpty()) "Escolha uma pasta ou arquivos. O OmniCore identifica o sistema sem obrigar você a separar a biblioteca por console."
                        else "Mude o sistema selecionado ou limpe a busca.",
                        color = OmniTextSoft
                    )
                    if (games.isEmpty()) Button(onClick = onImport, enabled = !importing) { Text("Adicionar jogos") }
                }
            }
        } else {
            items(shown, key = { it.id }) { game ->
                OmniGameCard(game, onPlay, { pendingRemoval = game })
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }

    pendingRemoval?.let { game ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = OmniPanelStrong,
            title = { Text("Remover da biblioteca?") },
            text = { Text("O arquivo ${game.fileName} não será apagado.", color = OmniTextSoft) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(game)
                    pendingRemoval = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun OmniHero(games: List<GameEntry>) {
    val represented = games.map { it.system }.distinct().size
    Card(
        colors = CardDefaults.cardColors(containerColor = OmniHeroPanel),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("OMNICORE HUB", color = OmniAccent2, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            Text("Uma biblioteca. Vários sistemas.", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${games.size} jogo(s) • $represented sistema(s) • PS1 estável • N64 manutenção • PS2 Alpha",
                color = OmniTextSoft,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun OmniGameCard(game: GameEntry, onPlay: (GameEntry) -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OmniPanel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(OmniBadge),
                contentAlignment = Alignment.Center
            ) {
                Text(game.system.shortName, color = Color.White, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f)) {
                Text(game.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(game.system.displayName)
                        if (game.sizeBytes > 0L) append(" • ").append(formatBytes(game.sizeBytes))
                    },
                    color = Color(0xFF7E879F),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onRemove) { Text("Remover") }
            Button(onClick = { onPlay(game) }) { Text("Jogar") }
        }
    }
}

@Composable
private fun OmniSystems(
    games: List<GameEntry>,
    onN64Settings: () -> Unit,
    onPS2Settings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("Sistemas", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Cada console mantém core, armazenamento, controles e perfil de performance próprios.", color = OmniTextSoft)
        }
        items(CoreRegistry.all()) { info ->
            val count = games.count { it.system == info.system }
            val status = when (info.state) {
                CoreState.READY -> "Funcional"
                CoreState.EXPERIMENTAL -> "Alpha"
                CoreState.PLANNED -> "Planejado"
            }
            OmniCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.system.displayName, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                        Text(info.name, color = OmniTextSoft, style = MaterialTheme.typography.bodySmall)
                        Text("$count jogo(s) na biblioteca", color = Color(0xFF7E879F), style = MaterialTheme.typography.labelSmall)
                    }
                    AssistChip(onClick = {}, label = { Text(status) })
                }
                when (info.system) {
                    ConsoleSystem.NINTENDO_64 -> {
                        Text("Mupen64Plus-Next • runtime :n64 • N64 SmartPerf protegido", color = OmniTextSoft)
                        Button(onClick = onN64Settings) { Text("Configurar Nintendo 64") }
                    }
                    ConsoleSystem.PLAYSTATION_2 -> {
                        Text("Play! pinado • runtime :ps2 • DualShock 2 touch • SmartPerf PS2", color = OmniTextSoft)
                        Button(onClick = onPS2Settings) { Text("Configurar PlayStation 2") }
                    }
                    ConsoleSystem.PLAYSTATION_1 ->
                        Text("PCSX-ReARMed • CUE/BIN • CHD/PBP • saves/estados • BIOS opcional", color = OmniTextSoft)
                    ConsoleSystem.PSP ->
                        Text("PPSSPP v1.20.4 • runtime :psp • OpenGL ES • resolução nativa 1×", color = OmniTextSoft)
                    else ->
                        Text("O sistema já faz parte da arquitetura/biblioteca, mas o backend ainda não foi integrado.", color = OmniTextSoft)
                }
            }
        }
    }
}

@Composable
private fun OmniSettings(
    biosCount: Int,
    onImportBios: () -> Unit,
    onN64Settings: () -> Unit,
    onPS2Settings: () -> Unit
) {
    val context = LocalContext.current
    var ps1Preset by remember { mutableStateOf(Ps1Settings.readPreset(context)) }
    var ps1Aspect by remember { mutableStateOf(Ps1Settings.readAspectMode(context)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Ajustes", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Configuração por sistema. Ajustes de um console não vazam para outro.", color = OmniTextSoft)
        }
        item {
            OmniCard {
                Text("PlayStation 1", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text("BIOS: $biosCount arquivo(s) importado(s)", color = OmniTextSoft)
                Button(onClick = onImportBios) { Text("Importar BIOS PS1") }
                Text("Perfil", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.Preset.entries.filter { it != Ps1Settings.Preset.CUSTOM }) { preset ->
                        FilterChip(
                            selected = ps1Preset == preset,
                            onClick = {
                                Ps1Settings.savePreset(context, preset)
                                ps1Preset = preset
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }
                Text("Tela", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.AspectMode.entries) { mode ->
                        FilterChip(
                            selected = ps1Aspect == mode,
                            onClick = {
                                Ps1Settings.saveAspectMode(context, mode)
                                ps1Aspect = mode
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }
        item {
            OmniCard {
                Text("Nintendo 64", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text("SmartPerf, CPU, RSP, resolução, controles e Controller Pak pertencem somente ao N64.", color = OmniTextSoft)
                Button(onClick = onN64Settings) { Text("Abrir ajustes N64") }
            }
        }
        item {
            OmniCard {
                Text("PlayStation 2", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text("Presets, renderer, resolução, Intro OmniCore, áudio e DualShock 2 touch pertencem somente ao PS2.", color = OmniTextSoft)
                Button(onClick = onPS2Settings) { Text("Abrir ajustes PS2") }
            }
        }
        item {
            OmniCard {
                Text("OmniCore", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = OmniTextSoft)
                Text("Android 8+ • ARM64/ARMv7 • runtime nativo com compatibilidade 16 KB", color = Color(0xFF7E879F))
            }
        }
    }
}

@Composable
private fun OmniCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OmniPanel),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
