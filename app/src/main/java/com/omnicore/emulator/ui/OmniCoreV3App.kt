package com.omnicore.emulator.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnicore.emulator.BuildConfig
import com.omnicore.emulator.core.CoreRegistry
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.core.ps1.Ps1Core
import com.omnicore.emulator.library.RomDetector
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.settings.InputSettings
import com.omnicore.emulator.settings.Ps1Settings
import com.omnicore.emulator.storage.GameLibraryStore
import com.omnicore.emulator.storage.Ps1Files
import com.omnicore.emulator.storage.Ps1MediaLayout
import com.omnicore.emulator.storage.Ps1PlaylistMedia
import com.omnicore.emulator.storage.SafGameSource
import com.omnicore.emulator.update.UpdateManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HubScreen { LIBRARY, CORES, TUNING }
private enum class LibrarySort { RECENT, TITLE, SIZE }

private val HubPanel = Color(0xEB121526)
private val HubPanelStrong = Color(0xF51A1D32)
private val HubSoft = Color(0xFFADB5CF)
private val HubPurple = Color(0xFF9879FF)
private val HubCyan = Color(0xFF57D8FF)

@Composable
fun OmniCoreV3App() {
    val context = LocalContext.current
    val store = remember { GameLibraryStore(context) }
    val ioScope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var filter by remember { mutableStateOf<ConsoleSystem?>(null) }
    var screen by remember { mutableStateOf(HubScreen.LIBRARY) }
    var message by remember { mutableStateOf<String?>(null) }
    var importDialog by remember { mutableStateOf(false) }
    var biosCount by remember { mutableIntStateOf(0) }
    var ps1Ready by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val startup = withContext(Dispatchers.IO) {
            Triple(store.load(), Ps1Files.biosFiles(context).size, NativeBridge.hasPs1Core())
        }
        games = startup.first
        biosCount = startup.second
        ps1Ready = startup.third
    }

    fun persist(additions: List<GameEntry>, success: String) {
        if (additions.isEmpty()) return
        games = (games + additions).distinctBy { "${it.uri}|${it.folderUri.orEmpty()}" }
        val snapshot = games
        ioScope.launch(Dispatchers.IO) { store.save(snapshot) }
        message = success
    }

    fun ps1Entries(plan: Ps1MediaLayout.Plan, folderUri: String? = null): List<GameEntry> =
        plan.sets.map { media ->
            val documents = media.allDocuments
            GameEntry(
                id = UUID.randomUUID().toString(),
                title = media.primary.name.substringBeforeLast('.', media.primary.name),
                fileName = media.primary.name,
                uri = media.primary.uri.toString(),
                system = ConsoleSystem.PLAYSTATION_1,
                sizeBytes = documents.sumOf { it.sizeBytes },
                folderUri = folderUri,
                companionUris = if (folderUri == null && media.kind != Ps1MediaLayout.Kind.SINGLE) {
                    documents.map { it.uri.toString() }
                } else {
                    emptyList()
                }
            )
        }

    fun importPs1Plan(plan: Ps1MediaLayout.Plan, folderUri: String? = null, sourceLabel: String): Boolean {
        val ambiguousBins = plan.warnings.firstOrNull { it.contains("vários BIN sem CUE", ignoreCase = true) }
        if (ambiguousBins != null) {
            message = ambiguousBins
            return true
        }
        val additions = ps1Entries(plan, folderUri)
        if (additions.isEmpty()) {
            message = plan.warnings.firstOrNull() ?: "Nenhuma mídia PS1 compatível foi reconhecida."
            return true
        }
        val descriptorCount = plan.sets.count { it.kind != Ps1MediaLayout.Kind.SINGLE }
        val warning = plan.warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
        val details = if (descriptorCount > 0) {
            "$descriptorCount conjunto(s) CUE/CCD mantidos com suas faixas."
        } else {
            "${additions.size} imagem(ns) PS1 reconhecida(s)."
        }
        persist(additions, buildString {
            append(sourceLabel).append(": ").append(details)
            if (!warning.isNullOrBlank()) append(" Aviso: ").append(warning)
        })
        return true
    }

    fun importPs1Playlists(
        playlists: List<SafGameSource.Document>,
        documents: List<SafGameSource.Document>,
        folderUri: String? = null,
        sourceLabel: String
    ): Boolean {
        if (playlists.isEmpty()) return false
        val additions = mutableListOf<GameEntry>()
        playlists.forEach { playlist ->
            val plan = Ps1PlaylistMedia.plan(context, playlist, documents).getOrElse { error ->
                message = "${playlist.name}: ${error.message ?: "playlist multi-disc inválida"}"
                return true
            }
            additions += GameEntry(
                id = UUID.randomUUID().toString(),
                title = playlist.name.substringBeforeLast('.', playlist.name),
                fileName = playlist.name,
                uri = playlist.uri.toString(),
                system = ConsoleSystem.PLAYSTATION_1,
                sizeBytes = plan.stagingDocuments.distinctBy { it.uri.toString() }.sumOf { it.sizeBytes },
                folderUri = folderUri,
                companionUris = if (folderUri == null) plan.stagingDocuments.map { it.uri.toString() } else emptyList()
            )
        }
        persist(
            additions,
            "$sourceLabel: ${additions.size} jogo(s) multi-disc adicionado(s). A troca de disco ficará no Quick Menu durante a partida."
        )
        return true
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val docs = uris.map { SafGameSource.metadata(context, it) }
        if (importPs1Playlists(docs.filter { it.extension == "m3u" }, docs, sourceLabel = "Seleção PS1")) {
            return@rememberLauncherForActivityResult
        }
        val hasPs1Descriptor = docs.any { it.extension in Ps1Core.DESCRIPTOR_EXTENSIONS }
        if (filter == ConsoleSystem.PLAYSTATION_1 || hasPs1Descriptor) {
            importPs1Plan(Ps1MediaLayout.plan(docs), sourceLabel = "Seleção PS1")
            return@rememberLauncherForActivityResult
        }

        val additions = docs.mapNotNull { doc ->
            val system = filter ?: RomDetector.detect(doc.name)
            system?.let {
                GameEntry(
                    id = UUID.randomUUID().toString(),
                    title = doc.name.substringBeforeLast('.', doc.name),
                    fileName = doc.name,
                    uri = doc.uri.toString(),
                    system = it,
                    sizeBytes = doc.sizeBytes
                )
            }
        }
        if (additions.isEmpty()) message = "Nenhum arquivo compatível foi reconhecido."
        else persist(additions, "${additions.size} jogo(s) adicionado(s).")
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val docs = runCatching {
            SafGameSource.listDirectChildren(context, treeUri).filterNot { it.isDirectory }
        }.getOrElse {
            message = it.message ?: "Não consegui ler a pasta selecionada."
            return@rememberLauncherForActivityResult
        }
        if (importPs1Playlists(
                playlists = docs.filter { it.extension == "m3u" },
                documents = docs,
                folderUri = treeUri.toString(),
                sourceLabel = "Pasta PS1"
            )) {
            return@rememberLauncherForActivityResult
        }
        importPs1Plan(
            plan = Ps1MediaLayout.plan(docs),
            folderUri = treeUri.toString(),
            sourceLabel = "Pasta PS1"
        )
    }

    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Ps1Files.importBios(context, uri)
                .onSuccess {
                    biosCount = Ps1Files.biosFiles(context).size
                    message = "BIOS importada: ${it.name}"
                }
                .onFailure { message = it.message ?: "Não consegui importar essa BIOS." }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0xFF090A17), Color(0xFF0A0D19), Color(0xFF05060B))
            )
        )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { HubTopBar(screen, games.size) { importDialog = true } },
            bottomBar = {
                NavigationBar(containerColor = Color(0xF20D1020), tonalElevation = 10.dp) {
                    NavigationBarItem(
                        selected = screen == HubScreen.LIBRARY,
                        onClick = { screen = HubScreen.LIBRARY },
                        icon = { Text("▦") },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = screen == HubScreen.CORES,
                        onClick = { screen = HubScreen.CORES },
                        icon = { Text("◉") },
                        label = { Text("Cores") }
                    )
                    NavigationBarItem(
                        selected = screen == HubScreen.TUNING,
                        onClick = { screen = HubScreen.TUNING },
                        icon = { Text("⌁") },
                        label = { Text("Tuning") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    HubScreen.LIBRARY -> HubLibrary(
                        games = games,
                        selected = filter,
                        ps1Ready = ps1Ready,
                        onFilter = { filter = it },
                        onImport = { importDialog = true },
                        onPlay = { game ->
                            val core = CoreRegistry.forSystem(game.system)
                            if (core == null || !core.isAvailable()) {
                                message = if (game.system == ConsoleSystem.PLAYSTATION_1) {
                                    "Core PS1 não carregado. Instale o APK completo gerado pelo Android Build."
                                } else {
                                    "O core de ${game.system.displayName} ainda está planejado."
                                }
                            } else {
                                core.launch(context, game).exceptionOrNull()?.let {
                                    message = it.message ?: "Falha ao iniciar ${game.title}."
                                }
                            }
                        },
                        onRemove = { game ->
                            games = games.filterNot { it.id == game.id }
                            val snapshot = games
                            ioScope.launch(Dispatchers.IO) { store.save(snapshot) }
                        }
                    )
                    HubScreen.CORES -> HubCores()
                    HubScreen.TUNING -> HubTuning(
                        biosCount = biosCount,
                        gameCount = games.size,
                        onImportBios = { biosPicker.launch(arrayOf("application/octet-stream", "*/*")) }
                    )
                }
            }
        }

        if (importDialog) {
            AlertDialog(
                onDismissRequest = { importDialog = false },
                containerColor = HubPanelStrong,
                title = { Text("Adicionar jogo", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Para PS1 em CUE/BIN ou CCD/IMG/SUB, escolha a pasta inteira ou selecione o conjunto completo. O OmniCore mantém descritor e faixas unidos.",
                            color = HubSoft
                        )
                        Button(
                            onClick = { importDialog = false; folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Escolher pasta PS1") }
                        Button(
                            onClick = { importDialog = false; filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29304B))
                        ) { Text("Selecionar arquivos") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Fechar") } }
            )
        }

        message?.let { text ->
            AlertDialog(
                onDismissRequest = { message = null },
                containerColor = HubPanelStrong,
                title = { Text("OmniCore") },
                text = { Text(text, color = HubSoft) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
            )
        }
    }
}

@Composable
private fun HubTopBar(screen: HubScreen, count: Int, onImport: () -> Unit) {
    Surface(color = Color(0xED0B0E1B), tonalElevation = 9.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(43.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(HubPurple, HubCyan))),
                contentAlignment = Alignment.Center
            ) {
                Text("O", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text("OmniCore", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (screen) {
                        HubScreen.LIBRARY -> "$count jogo(s) • Runtime v7"
                        HubScreen.CORES -> "Motores de emulação"
                        HubScreen.TUNING -> "Graphics & Performance Lab"
                    },
                    color = HubSoft,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (screen == HubScreen.LIBRARY) {
                Button(onClick = onImport, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("+ Jogo")
                }
            }
        }
    }
}

@Composable
private fun HubLibrary(
    games: List<GameEntry>,
    selected: ConsoleSystem?,
    ps1Ready: Boolean?,
    onFilter: (ConsoleSystem?) -> Unit,
    onImport: () -> Unit,
    onPlay: (GameEntry) -> Unit,
    onRemove: (GameEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var pendingRemoval by remember { mutableStateOf<GameEntry?>(null) }
    val shown = remember(games, selected, query, sort) {
        val filtered = games.asSequence()
            .filter { selected == null || it.system == selected }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.fileName.contains(query.trim(), ignoreCase = true) }
            .toList()
        when (sort) {
            LibrarySort.RECENT -> filtered.sortedByDescending { it.addedAt }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item { EngineHero(ps1Ready) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Buscar na biblioteca") },
                    placeholder = { Text("Nome do jogo ou arquivo") }
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = selected == null, onClick = { onFilter(null) }, label = { Text("Todos") }) }
                    items(ConsoleSystem.entries) { system ->
                        FilterChip(selected = selected == system, onClick = { onFilter(system) }, label = { Text(system.shortName) })
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = sort == LibrarySort.RECENT, onClick = { sort = LibrarySort.RECENT }, label = { Text("Recentes") }) }
                    item { FilterChip(selected = sort == LibrarySort.TITLE, onClick = { sort = LibrarySort.TITLE }, label = { Text("A–Z") }) }
                    item { FilterChip(selected = sort == LibrarySort.SIZE, onClick = { sort = LibrarySort.SIZE }, label = { Text("Tamanho") }) }
                }
            }
            if (shown.isEmpty()) {
                item {
                    HubCard {
                        Text(if (games.isEmpty()) "Sua biblioteca está pronta" else "Nenhum jogo encontrado", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (games.isEmpty()) "Adicione uma pasta PS1 ou um arquivo compatível. O PS1 é o primeiro motor funcional do OmniCore."
                            else "Tente limpar a busca ou mudar o filtro de sistema.",
                            color = HubSoft
                        )
                        if (games.isEmpty()) Button(onClick = onImport) { Text("Adicionar jogo") }
                    }
                }
            } else {
                items(shown, key = { it.id }) { game ->
                    Card(
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0x223B4262), RoundedCornerShape(19.dp)),
                        colors = CardDefaults.cardColors(containerColor = HubPanel),
                        shape = RoundedCornerShape(19.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)
                        ) {
                            Box(
                                Modifier.size(60.dp).clip(RoundedCornerShape(17.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF332B66), Color(0xFF17394B)))),
                                contentAlignment = Alignment.Center
                            ) { Text(game.system.shortName, color = Color.White, fontWeight = FontWeight.Black) }
                            Column(Modifier.weight(1f)) {
                                Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                Text(
                                    buildString {
                                        append(game.system.displayName)
                                        if (game.folderUri != null) append(" • pasta")
                                        if (game.sizeBytes > 0) append(" • ").append(formatHubBytes(game.sizeBytes))
                                    },
                                    color = Color(0xFF747D9A), style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { pendingRemoval = game }) { Text("Remover") }
                            Button(onClick = { onPlay(game) }) { Text("Jogar") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(5.dp)) }
        }

        pendingRemoval?.let { game ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                containerColor = HubPanelStrong,
                title = { Text("Remover da biblioteca?") },
                text = { Text("${game.title} será removido apenas da biblioteca. O arquivo do jogo não será apagado.", color = HubSoft) },
                confirmButton = {
                    TextButton(onClick = { onRemove(game); pendingRemoval = null }) { Text("Remover") }
                },
                dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancelar") } }
            )
        }
    }
}

@Composable
private fun EngineHero(ps1Ready: Boolean?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF261F53), Color(0xFF0D3040))),
                RoundedCornerShape(22.dp)
            ).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("PLAYSTATION ENGINE", color = HubCyan, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                Text("PCSX-ReARMed", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
                Text("CUE/BIN • CCD/IMG/SUB • CHD • PBP • EGL/GLES", color = HubSoft, style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(onClick = {}, label = {
                Text(when (ps1Ready) { true -> "ONLINE"; false -> "OFFLINE"; null -> "VERIFICANDO" })
            })
        }
    }
}

@Composable
private fun HubCores() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Text("Cores", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Arquitetura multi-core isolada: cada console ganha seu próprio backend e perfil de otimização.", color = HubSoft)
        }
        items(CoreRegistry.all()) { info ->
            val ready = remember(info.id) { CoreRegistry.forSystem(info.system)?.isAvailable() == true }
            val status = when {
                info.state == CoreState.READY && ready -> "Pronto"
                info.state == CoreState.READY -> "Core ausente"
                info.state == CoreState.EXPERIMENTAL -> "Experimental"
                else -> "Planejado"
            }
            HubCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.name, fontWeight = FontWeight.Bold)
                        Text(info.system.displayName, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                        if (info.state == CoreState.READY) Text(info.version, color = Color(0xFF737C98), style = MaterialTheme.typography.labelSmall)
                    }
                    AssistChip(onClick = {}, label = { Text(status) })
                }
            }
        }
    }
}

@Composable
private fun HubTuning(biosCount: Int, gameCount: Int, onImportBios: () -> Unit) {
    val context = LocalContext.current
    val device = remember { PerformanceManager.profile(context) }
    var perfMode by remember { mutableStateOf(PerformanceManager.readUserMode(context)) }
    var config by remember { mutableStateOf(Ps1Settings.resolve(context)) }
    var inputConfig by remember { mutableStateOf(InputSettings.resolve(context)) }
    var updateStatus by remember { mutableStateOf("Canal DEV • pronto para verificar") }
    var updateRelease by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
    var cacheStatus by remember { mutableStateOf("CUE/BIN será reutilizado após a primeira preparação.") }

    fun checkUpdate() {
        updateStatus = "Verificando GitHub Releases…"
        UpdateManager.checkForUpdate(context) { result ->
            when (result) {
                is UpdateManager.CheckResult.Available -> {
                    updateRelease = result.release
                    updateStatus = "OmniCore ${result.release.version} disponível"
                }
                is UpdateManager.CheckResult.Current -> {
                    updateRelease = null
                    updateStatus = "Você já está na versão DEV mais recente (${result.version})."
                }
                is UpdateManager.CheckResult.Error -> updateStatus = result.message
            }
        }
    }

    fun installUpdate(release: UpdateManager.ReleaseInfo) {
        UpdateManager.install(context, release) { result ->
            when (result) {
                is UpdateManager.InstallResult.Progress -> updateStatus = result.message
                UpdateManager.InstallResult.NeedsUnknownSourcesPermission -> {
                    updateStatus = "Autorize o OmniCore a instalar updates e toque em Atualizar novamente."
                    context.startActivity(UpdateManager.unknownSourcesIntent(context))
                }
                UpdateManager.InstallResult.InstallerStarted ->
                    updateStatus = "Confirme a atualização na tela oficial do Android."
                is UpdateManager.InstallResult.Error -> updateStatus = result.message
            }
        }
    }

    fun refresh() { config = Ps1Settings.resolve(context) }
    fun refreshInput() { inputConfig = InputSettings.resolve(context) }
    fun saveCustom(next: Ps1Settings.Config) {
        Ps1Settings.saveCustom(context, next.copy(preset = Ps1Settings.Preset.CUSTOM))
        refresh()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Text("Tuning Center", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            Text("Opções reais do core PS1, além do SmartPerf do frontend.", color = HubSoft)
        }
        item {
            HubSection("Presets PS1", config.preset.subtitle) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.Preset.entries.filter { it != Ps1Settings.Preset.CUSTOM }) { preset ->
                        FilterChip(
                            selected = config.preset == preset,
                            onClick = { Ps1Settings.savePreset(context, preset); refresh() },
                            label = { Text(preset.label) }
                        )
                    }
                    if (config.preset == Ps1Settings.Preset.CUSTOM) {
                        item { AssistChip(onClick = {}, label = { Text("Custom") }) }
                    }
                }
            }
        }
        item {
            HubSection("Gráficos", "Renderer NEON e opções de fidelidade do PCSX-ReARMed.") {
                SettingSwitch("Resolução aprimorada", "Renderiza 3D em resolução interna maior.", config.enhancedResolution) {
                    saveCustom(config.copy(enhancedResolution = it))
                }
                SettingSwitch("Speed hack de resolução", "Acelera enhanced resolution com menor compatibilidade.", config.enhancedSpeedHack) {
                    saveCustom(config.copy(enhancedSpeedHack = it))
                }
                SettingSwitch("Ajuste de texturas", "Corrige texturas em enhanced resolution.", config.textureAdjustment) {
                    saveCustom(config.copy(textureAdjustment = it))
                }
                SettingSwitch("Dithering PS1", "Mantém gradações e aparência próximas ao hardware original.", config.dithering) {
                    saveCustom(config.copy(dithering = it))
                }
                SettingSwitch("GPU em thread", "Executa comandos gráficos em thread auxiliar.", config.threadedGpu) {
                    saveCustom(config.copy(threadedGpu = it))
                }
            }
        }
        item {
            HubSection("Tela e inicialização", "Mantém a base de vídeo 0.7 e adiciona apresentação configurável.") {
                SettingSwitch(
                    "Boot clássico do PS1",
                    "Com uma BIOS real válida, mostra a tela/logo clássico antes do jogo. Pode ser desligado para máxima compatibilidade.",
                    config.showBiosBootLogo
                ) {
                    Ps1Settings.saveBiosBootLogo(context, it)
                    refresh()
                }
                Text("Formato de tela", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Ps1Settings.AspectMode.entries) { mode ->
                        FilterChip(
                            selected = config.aspectMode == mode,
                            onClick = { Ps1Settings.saveAspectMode(context, mode); refresh() },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(
                    when (config.aspectMode) {
                        Ps1Settings.AspectMode.ORIGINAL_4_3 -> "4:3 preserva a proporção original do console."
                        Ps1Settings.AspectMode.WIDE_16_9 -> "16:9 expande a apresentação. Não é um patch widescreen de geometria 3D por jogo."
                        Ps1Settings.AspectMode.FULLSCREEN -> "Tela cheia preenche toda a área disponível e pode deformar a proporção."
                    },
                    color = HubSoft,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            HubSection("Início rápido PS1", "CUE/BIN é preparado uma vez e reaproveitado enquanto a origem não mudar.") {
                Text(cacheStatus, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    val cacheDir = context.cacheDir.resolve("ps1-disc-cache")
                    val cleared = !cacheDir.exists() || cacheDir.deleteRecursively()
                    cacheStatus = if (cleared) "Cache CUE/BIN limpo. O próximo boot fará uma nova preparação." else "Não consegui limpar todo o cache agora."
                }) { Text("Limpar cache CUE/BIN") }
                Text(
                    "O Android ainda pode limpar este cache automaticamente quando precisar de espaço.",
                    color = Color(0xFF737C98),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            HubSection("Performance e áudio", "Controles de estabilidade para aparelhos com diferentes limites térmicos.") {
                SettingSwitch("SPU em thread", "Move parte da emulação de áudio para outra thread.", config.threadedSpu) {
                    saveCustom(config.copy(threadedSpu = it))
                }
                SettingSwitch("Frameskip automático", "Usa o estado real do buffer de áudio para decidir quando aliviar vídeo.", config.frameskipAuto) {
                    saveCustom(config.copy(frameskipAuto = it))
                }
                Text("Interpolação", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("simple" to "Simples", "gaussian" to "Gaussiana", "cubic" to "Cúbica", "off" to "Off")) { option ->
                        FilterChip(
                            selected = config.interpolation == option.first,
                            onClick = { saveCustom(config.copy(interpolation = option.first)) },
                            label = { Text(option.second) }
                        )
                    }
                }
                Text("CD read-ahead", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0, 12, 16, 32, 64)) { value ->
                        FilterChip(
                            selected = config.cdReadAhead == value,
                            onClick = { saveCustom(config.copy(cdReadAhead = value)) },
                            label = { Text(value.toString()) }
                        )
                    }
                }
            }
        }
        item {
            HubSection("Controles", "Compatibilidade para PS1 antigo, DualShock e controles Android.") {
                SettingSwitch("DualShock / analógico", "Ativa o tipo DualShock no core e os eixos analógicos.", config.dualShock) {
                    Ps1Settings.saveDualShock(context, it)
                    refresh()
                }
                Text("Comportamento do analógico esquerdo", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(InputSettings.AnalogMode.entries) { mode ->
                        FilterChip(
                            selected = inputConfig.analogMode == mode,
                            onClick = { InputSettings.saveAnalogMode(context, mode); refreshInput() },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(inputConfig.analogMode.subtitle, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                SettingSwitch(
                    "Mostrar setas do D-pad",
                    "No modo Inteligente elas podem ficar ocultas: o analógico continua enviando direções digitais para jogos antigos.",
                    inputConfig.showDpad
                ) {
                    InputSettings.saveShowDpad(context, it)
                    refreshInput()
                }
                Text("Tamanho do touch", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0.85f to "85%", 1f to "100%", 1.15f to "115%")) { option ->
                        FilterChip(
                            selected = kotlin.math.abs(inputConfig.touchScale - option.first) < 0.02f,
                            onClick = { InputSettings.saveTouchScale(context, option.first); refreshInput() },
                            label = { Text(option.second) }
                        )
                    }
                }
                Text("Opacidade", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0.55f to "55%", 0.70f to "70%", 0.85f to "85%", 1f to "100%")) { option ->
                        FilterChip(
                            selected = kotlin.math.abs(inputConfig.touchOpacity - option.first) < 0.03f,
                            onClick = { InputSettings.saveTouchOpacity(context, option.first); refreshInput() },
                            label = { Text(option.second) }
                        )
                    }
                }
                SettingSwitch("Feedback tátil", "Vibração curta ao tocar botões e capturar o analógico.", inputConfig.haptics) {
                    InputSettings.saveHaptics(context, it)
                    refreshInput()
                }
                Text(
                    "No jogo, o botão ⋮ abre o Quick Menu com save/load, status, editor, visual, presets, cheats e saída. Perfis visuais e posições ficam salvos por jogo.",
                    color = Color(0xFF737C98),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            HubSection("SmartPerf 3", device.summary) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PerformanceManager.UserMode.entries) { mode ->
                        FilterChip(
                            selected = perfMode == mode,
                            onClick = {
                                perfMode = mode
                                PerformanceManager.saveUserMode(context, mode)
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text("AAudio adaptativo • frame pacing • ADPF • controle térmico • zero-copy quando disponível", color = HubSoft, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            HubSection("Atualizações", "Canal DEV assinado de forma estável a partir da 0.6.0.") {
                Text(updateStatus, color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { checkUpdate() }) { Text("Verificar atualização") }
                updateRelease?.let { release ->
                    Button(onClick = { installUpdate(release) }) {
                        Text("Atualizar para ${release.version}")
                    }
                }
                Text(
                    "Builds DEV 0.6+ podem atualizar por cima mantendo dados. O Android ainda mostra a confirmação oficial de instalação.",
                    color = Color(0xFF737C98),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            HubSection(
                "Informações do OmniCore",
                "Build DEV ${BuildConfig.VERSION_NAME} • Runtime v7 • estado real do app e do aparelho"
            ) {
                val ps1Ready = NativeBridge.hasPs1Core()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { AssistChip(onClick = {}, label = { Text(if (ps1Ready) "PS1 ONLINE" else "PS1 OFFLINE") }) }
                    item { AssistChip(onClick = {}, label = { Text("DEV") }) }
                    item { AssistChip(onClick = {}, label = { Text("API ${Build.VERSION.SDK_INT}") }) }
                }

                Text("Versão do app", fontWeight = FontWeight.Bold)
                Text(
                    "OmniCore ${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE}",
                    color = HubSoft,
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Runtime e engine", fontWeight = FontWeight.Bold)
                Text("Runtime nativo: ${NativeBridge.runtimeVersion()}", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text(
                    "PlayStation 1: ${if (ps1Ready) "PCSX-ReARMed pinned da2cb8e pronto" else "core não carregado neste APK"}",
                    color = HubSoft,
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Aparelho", fontWeight = FontWeight.Bold)
                Text(
                    "Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT} • ${Build.SUPPORTED_ABIS.joinToString(" / ")}",
                    color = HubSoft,
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Perfil SmartPerf: ${device.summary}", color = HubSoft, style = MaterialTheme.typography.bodySmall)

                Text("Biblioteca e BIOS", fontWeight = FontWeight.Bold)
                Text("$gameCount jogo(s) cadastrados • $biosCount BIOS .bin importada(s)", color = HubSoft, style = MaterialTheme.typography.bodySmall)

                Text("Novidades da linha 0.9.3", fontWeight = FontWeight.Bold)
                Text("• Clean Overlay com Quick Menu compacto e HUD de performance oculto por padrão.", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("• Presets Limpo, Compacto, Padrão, Mão esquerda, Mão direita e Tablet com perfil por jogo.", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("• Labels, ombros, START/SELECT, D-pad e overlay completo podem ser ocultados por jogo.", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("• Gestos de 3 dedos abrem o menu; 4 dedos alternam o modo ultra imersivo.", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("• Cheat Manager por jogo integrado ao retro_cheat_set/reset do PCSX-ReARMed.", color = HubSoft, style = MaterialTheme.typography.bodySmall)
                Text("• Pré-aquecimento de caches e prefetch de save state da linha 0.9.1 continuam ativos.", color = HubSoft, style = MaterialTheme.typography.bodySmall)

                Text("Estado do projeto", fontWeight = FontWeight.Bold)
                Text(
                    "PS1 é o backend funcional atual. Os demais sistemas continuam separados como planejados até cada core passar por integração e validação própria.",
                    color = HubSoft,
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Desenvolvido por Mauricio.gamedev • @mauricio-gamedev", color = HubCyan, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onImportBios) { Text("Importar BIOS próprio") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HubPanel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun HubSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    HubCard {
        Text(title, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = HubSoft, style = MaterialTheme.typography.bodySmall)
        content()
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HubSoft, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatHubBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
