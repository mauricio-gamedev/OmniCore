package com.omnicore.emulator.library

import android.content.Context
import android.net.Uri
import com.omnicore.emulator.core.n64.N64RomValidator
import com.omnicore.emulator.core.ps2.PS2IsoValidator
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.storage.Ps1MediaLayout
import com.omnicore.emulator.storage.Ps1PlaylistMedia
import com.omnicore.emulator.storage.SafGameSource
import java.util.UUID

/**
 * Shared import pipeline for the OmniCore library.
 *
 * Import is system-neutral: console filters never blindly force a file to a
 * backend. N64 is signature-detected, PS1 CUE/CCD/M3U media is grouped, PS2 ISO
 * media is content-detected through ISO9660 SYSTEM.CNF/BOOT2, and ambiguous
 * compressed/container extensions remain explicit user choices.
 */
object LibraryImportEngine {
    data class Report(
        val games: List<GameEntry>,
        val scanned: Int,
        val skipped: Int,
        val warnings: List<String> = emptyList()
    ) {
        val summary: String
            get() = when {
                games.isEmpty() && warnings.isNotEmpty() -> warnings.first()
                games.isEmpty() -> "Nenhum jogo reconhecido."
                skipped > 0 -> "${games.size} jogo(s) encontrado(s) • $skipped arquivo(s) ignorado(s)."
                else -> "${games.size} jogo(s) encontrado(s)."
            }
    }

    fun importFiles(
        context: Context,
        uris: List<Uri>,
        preferredSystem: ConsoleSystem? = null
    ): Report {
        val warnings = mutableListOf<String>()
        val docs = uris.mapNotNull { uri ->
            runCatching { SafGameSource.metadata(context, uri) }
                .onFailure { warnings += "Não consegui ler ${uri.lastPathSegment ?: "um arquivo"}." }
                .getOrNull()
        }
        return classify(
            context = context,
            docs = docs,
            folderUri = null,
            preferredSystem = preferredSystem,
            selectedUris = docs.map { it.uri.toString() },
            inheritedWarnings = warnings
        )
    }

    fun importFolder(
        context: Context,
        treeUri: Uri,
        preferredSystem: ConsoleSystem? = null
    ): Report {
        val docs = SafGameSource.listDirectChildren(context, treeUri).filterNot { it.isDirectory }
        return classify(
            context = context,
            docs = docs,
            folderUri = treeUri.toString(),
            preferredSystem = preferredSystem,
            selectedUris = emptyList(),
            inheritedWarnings = emptyList()
        )
    }

    private fun classify(
        context: Context,
        docs: List<SafGameSource.Document>,
        folderUri: String?,
        preferredSystem: ConsoleSystem?,
        selectedUris: List<String>,
        inheritedWarnings: List<String>
    ): Report {
        val games = mutableListOf<GameEntry>()
        val warnings = inheritedWarnings.toMutableList()
        val consumed = mutableSetOf<String>()

        // M3U is one library entry for the complete multi-disc set. The runtime
        // resolves the playlist and exposes disc switching in the PS1 Quick Menu.
        docs.filter { it.extension == "m3u" }.forEach { playlist ->
            val plan = Ps1PlaylistMedia.plan(context, playlist, docs).getOrElse { error ->
                warnings += "${playlist.name}: ${error.message ?: "playlist multi-disc inválida"}"
                consumed += playlist.uri.toString()
                return@forEach
            }
            val staging = plan.stagingDocuments.distinctBy { it.uri.toString() }
            games += GameEntry(
                id = UUID.randomUUID().toString(),
                title = cleanTitle(playlist.name),
                fileName = playlist.name,
                uri = playlist.uri.toString(),
                system = ConsoleSystem.PLAYSTATION_1,
                sizeBytes = staging.sumOf { it.sizeBytes },
                folderUri = folderUri,
                companionUris = if (folderUri == null) staging.map { it.uri.toString() } else emptyList()
            )
            plan.warnings.forEach { warnings += "${playlist.name}: $it" }
            staging.forEach { consumed += it.uri.toString() }
        }

        // Build PS1 CUE entries without returning early. N64/PS2 or other
        // systems in the exact same folder continue through the scanner.
        val cues = docs.filter { it.extension == "cue" && it.uri.toString() !in consumed }
        val referencedPs1Tracks = mutableSetOf<String>()
        cues.forEach { cue ->
            val cueRefs = mutableListOf<String>()
            runCatching { SafGameSource.readCueText(context, cue.uri) }
                .onSuccess { text ->
                    SafGameSource.cueReferences(text).forEach {
                        val normalized = SafGameSource.normalizeReference(it)
                        cueRefs += normalized
                        referencedPs1Tracks += normalized.lowercase()
                    }
                }
                .onFailure { warnings += "${cue.name}: não consegui validar as linhas FILE agora." }

            val companions = if (folderUri == null) {
                val wanted = cueRefs.map { it.lowercase() }.toSet()
                docs.filter { it.name.lowercase() in wanted }.map { it.uri.toString() }
            } else {
                emptyList()
            }
            games += GameEntry(
                id = UUID.randomUUID().toString(),
                title = cleanTitle(cue.name),
                fileName = cue.name,
                uri = cue.uri.toString(),
                system = ConsoleSystem.PLAYSTATION_1,
                sizeBytes = if (folderUri != null) 0L else docs.filter { it.uri.toString() == cue.uri.toString() || it.uri.toString() in companions }.sumOf { it.sizeBytes },
                folderUri = folderUri,
                companionUris = companions
            )
            consumed += cue.uri.toString()
        }

        docs.filter { it.name.lowercase() in referencedPs1Tracks }.forEach {
            consumed += it.uri.toString()
        }

        // CCD is paired only with its basename-matching IMG and optional SUB/SBI.
        val ccdPlan = Ps1MediaLayout.plan(docs.filter { it.uri.toString() !in consumed })
        ccdPlan.warnings.forEach(warnings::add)
        ccdPlan.sets.filter { it.kind == Ps1MediaLayout.Kind.CCD_SET }.forEach { media ->
            val setDocs = media.allDocuments.distinctBy { it.uri.toString() }
            games += GameEntry(
                id = UUID.randomUUID().toString(),
                title = cleanTitle(media.primary.name),
                fileName = media.primary.name,
                uri = media.primary.uri.toString(),
                system = ConsoleSystem.PLAYSTATION_1,
                sizeBytes = setDocs.sumOf { it.sizeBytes },
                folderUri = folderUri,
                companionUris = if (folderUri == null) setDocs.map { it.uri.toString() } else emptyList()
            )
            setDocs.forEach { consumed += it.uri.toString() }
        }

        docs.forEach { doc ->
            if (doc.uri.toString() in consumed) return@forEach
            if (doc.name.lowercase() in referencedPs1Tracks) {
                consumed += doc.uri.toString()
                return@forEach
            }

            // Nintendo 64 is recognized by actual ROM magic, not by its suffix.
            // This also detects .rom/.bin and ZIP/GZIP wrappers safely.
            val n64Candidate = doc.extension in N64RomValidator.commonExtensions ||
                doc.extension.isBlank()
            if (n64Candidate && N64RomValidator.isNintendo64(context, doc.uri, doc.name)) {
                games += entry(doc, ConsoleSystem.NINTENDO_64, folderUri = null)
                consumed += doc.uri.toString()
                return@forEach
            }

            // .iso is shared by several consoles. Only classify it as PS2 when
            // ISO9660 SYSTEM.CNF explicitly contains BOOT2. This keeps PS1/PSP/
            // Wii images from being routed to PCSX2 by extension alone.
            if (doc.extension == "iso" && PS2IsoValidator.isPlayStation2Iso(context, doc.uri, doc.name)) {
                games += entry(doc, ConsoleSystem.PLAYSTATION_2, folderUri = null)
                consumed += doc.uri.toString()
                return@forEach
            }

            val candidates = RomDetector.candidates(doc.name)
            val activeCandidates = candidates.filter { it in ACTIVE_SYSTEMS }
            val explicitOnly = doc.extension in EXPLICIT_SYSTEM_EXTENSIONS
            val system = when {
                preferredSystem != null && preferredSystem in candidates -> preferredSystem
                explicitOnly -> null
                activeCandidates.size == 1 -> activeCandidates.single()
                candidates.size == 1 -> candidates.single()
                else -> null
            }

            if (system != null) {
                if (system == ConsoleSystem.PLAYSTATION_1 && doc.extension == "bin" && cues.isNotEmpty()) {
                    consumed += doc.uri.toString()
                    return@forEach
                }
                games += entry(doc, system, if (system == ConsoleSystem.PLAYSTATION_1) folderUri else null)
                consumed += doc.uri.toString()
            }
        }

        if (cues.isEmpty()) {
            val looseBins = games.filter { it.system == ConsoleSystem.PLAYSTATION_1 && it.fileName.endsWith(".bin", true) }
            if (looseBins.size > 1) {
                games.removeAll(looseBins.toSet())
                warnings += "Há vários BIN sem CUE. Adicione o arquivo CUE para o PS1 saber a ordem das faixas."
            }
        }

        val skipped = (docs.size - consumed.size).coerceAtLeast(0)
        if (games.isEmpty() && warnings.isEmpty()) {
            warnings += "A pasta não contém ROMs reconhecidas pelos sistemas atuais do OmniCore."
        }
        return Report(games.distinctBy { it.uri }, docs.size, skipped, warnings.distinct())
    }

    private fun entry(
        doc: SafGameSource.Document,
        system: ConsoleSystem,
        folderUri: String?
    ) = GameEntry(
        id = UUID.randomUUID().toString(),
        title = cleanTitle(doc.name),
        fileName = doc.name,
        uri = doc.uri.toString(),
        system = system,
        sizeBytes = doc.sizeBytes,
        folderUri = folderUri
    )

    private fun cleanTitle(name: String): String =
        name.substringBeforeLast('.', name).replace('_', ' ').trim().ifBlank { name }

    private val EXPLICIT_SYSTEM_EXTENSIONS = setOf("bin", "chd", "cso")

    private val ACTIVE_SYSTEMS = setOf(
        ConsoleSystem.PLAYSTATION_1,
        ConsoleSystem.NINTENDO_64,
        ConsoleSystem.PLAYSTATION_2
    )
}
