#!/usr/bin/env python3
from pathlib import Path


def patch_file(path: Path, operations):
    text = path.read_text(encoding="utf-8")
    for old, new, label in operations:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: {label}: expected exactly one anchor, found {count}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")


activity = Path("app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt")
activity_ops = [
    (
        "import com.omnicore.emulator.storage.Ps1MediaLayout\nimport com.omnicore.emulator.storage.SafGameSource\n",
        "import com.omnicore.emulator.storage.Ps1MediaLayout\nimport com.omnicore.emulator.storage.Ps1PlaylistMedia\nimport com.omnicore.emulator.storage.Ps1DiscManifest\nimport com.omnicore.emulator.storage.SafGameSource\n",
        "M3U imports",
    ),
    (
        '''        statusView.text = when (extension) {\n            "cue" -> "PREP 1/3 • lendo CUE e faixas…"\n            "ccd" -> "PREP 1/3 • lendo CCD e imagem…"\n            else -> "PREP 1/3 • abrindo $gameTitle…"\n        }\n''',
        '''        statusView.text = when (extension) {\n            "cue" -> "PREP 1/3 • lendo CUE e faixas…"\n            "ccd" -> "PREP 1/3 • lendo CCD e imagem…"\n            "m3u" -> "PREP 1/3 • lendo playlist multi-disc…"\n            else -> "PREP 1/3 • abrindo $gameTitle…"\n        }\n''',
        "M3U preparation status",
    ),
    (
        '''            when (extension) {\n                "cue" -> prepareCueSession(uri, folderUri, companionUris)\n                "ccd" -> prepareCcdSession(uri, folderUri, companionUris)\n                else -> prepareSingleFileSession(uri, extension)\n            }\n''',
        '''            when (extension) {\n                "cue" -> prepareCueSession(uri, folderUri, companionUris)\n                "ccd" -> prepareCcdSession(uri, folderUri, companionUris)\n                "m3u" -> prepareM3uSession(uri, folderUri, companionUris)\n                else -> prepareSingleFileSession(uri, extension)\n            }\n''',
        "M3U preparation routing",
    ),
    (
        '''    private fun cueCacheDir(): File {\n''',
        '''    private fun prepareM3uSession(m3uUri: Uri, folderUri: Uri?, companionUris: List<Uri>): PreparedContent {\n        val descriptors = mutableListOf<ParcelFileDescriptor>()\n        val dir = m3uCacheDir()\n        return try {\n            ensurePreparationActive()\n            val sources = if (folderUri != null) {\n                SafGameSource.listDirectChildren(this, folderUri).filterNot { it.isDirectory }\n            } else {\n                (companionUris + m3uUri).distinctBy(Uri::toString).map { SafGameSource.metadata(this, it) }\n            }\n            val playlist = sources.firstOrNull { it.uri.toString() == m3uUri.toString() }\n                ?: SafGameSource.metadata(this, m3uUri)\n            val plan = Ps1PlaylistMedia.plan(this, playlist, sources).getOrThrow()\n            val playlistText = Ps1PlaylistMedia.readText(this, m3uUri)\n            val fingerprint = cueFingerprint(playlistText, plan.stagingDocuments)\n            val marker = File(dir, ".source-fingerprint")\n            val localM3u = File(dir, "game.m3u")\n\n            val cacheValid = runCatching {\n                marker.isFile && marker.readText(Charsets.UTF_8) == fingerprint &&\n                    localM3u.isFile && localM3u.length() > 0L &&\n                    validateM3uSession(localM3u).let { true }\n            }.getOrDefault(false)\n            if (cacheValid) {\n                statusView.post { statusView.text = "PREP 2/3 • cache multi-disc validado — início rápido" }\n                return PreparedContent(localM3u.absolutePath, emptyList(), dir, persistent = true)\n            }\n\n            runCatching { dir.deleteRecursively() }\n            require(dir.mkdirs() || dir.isDirectory) { "Não consegui criar o cache multi-disc." }\n            statusView.post {\n                statusView.text = "PREP 2/3 • preparando ${plan.manifest.discs.size} discos pela primeira vez…"\n            }\n\n            val payload = plan.stagingDocuments.filterNot { it.uri.toString() == m3uUri.toString() }\n            val duplicateNames = payload.groupBy { safeFileName(it.name).lowercase() }\n                .filterValues { docs -> docs.map { it.uri.toString() }.distinct().size > 1 }\n            require(duplicateNames.isEmpty()) {\n                "A playlist contém arquivos diferentes com o mesmo nome local: ${duplicateNames.keys.first()}."\n            }\n\n            payload.forEach { source ->\n                ensurePreparationActive()\n                stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors, forceCopy = true)\n            }\n\n            val rewritten = buildString {\n                plan.manifest.discs.forEachIndexed { index, disc ->\n                    disc.label?.takeIf { it.isNotBlank() }?.let { label ->\n                        append("#EXTINF:-1,")\n                            .append(label.replace('\\r', ' ').replace('\\n', ' '))\n                            .append('\\n')\n                    }\n                    append(safeFileName(plan.discDocuments[index].name)).append('\\n')\n                }\n            }\n            localM3u.writeText(rewritten, Charsets.UTF_8)\n            validateM3uSession(localM3u)\n            marker.writeText(fingerprint, Charsets.UTF_8)\n            ensurePreparationActive()\n            PreparedContent(localM3u.absolutePath, descriptors.toList(), dir, persistent = true)\n        } catch (error: Throwable) {\n            descriptors.forEach { runCatching { it.close() } }\n            runCatching { dir.deleteRecursively() }\n            throw error\n        }\n    }\n\n    private fun validateM3uSession(m3uFile: File) {\n        require(m3uFile.isFile && m3uFile.length() > 0L) { "A playlist M3U local ficou indisponível." }\n        val manifest = Ps1DiscManifest.parseM3u(m3uFile.readText(Charsets.UTF_8))\n        require(manifest.discs.size >= 2) { "A playlist local ficou sem pelo menos dois discos válidos." }\n        manifest.discs.forEach { disc ->\n            ensurePreparationActive()\n            require('/' !in disc.reference && '\\\\' !in disc.reference) { "Referência insegura no M3U local: ${disc.reference}" }\n            val image = File(m3uFile.parentFile, disc.reference)\n            require(image.parentFile?.canonicalFile == m3uFile.parentFile?.canonicalFile) {\n                "Referência externa no M3U local: ${disc.reference}"\n            }\n            require(image.isFile && image.length() > 0L) { "O disco '${disc.reference}' não ficou disponível no cache." }\n            when (image.extension.lowercase()) {\n                "cue" -> validateCueSession(image)\n                "ccd" -> validateCcdSession(image)\n                else -> runCatching {\n                    java.io.RandomAccessFile(image, "r").use { file ->\n                        val length = file.length()\n                        require(length > 0L)\n                        file.seek((length - 1L).coerceAtLeast(0L))\n                        require(file.read() >= 0)\n                    }\n                }.getOrElse {\n                    error("O disco '${disc.reference}' não aceita leitura aleatória necessária para emulação de CD.")\n                }\n            }\n        }\n    }\n\n    private fun m3uCacheDir(): File {\n        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9_-]"), "_")\n        return File(cacheDir, "ps1-disc-cache/$safeKey-multidisc")\n    }\n\n    private fun cueCacheDir(): File {\n''',
        "M3U staging implementation",
    ),
]
patch_file(activity, activity_ops)

ui = Path("app/src/main/java/com/omnicore/emulator/ui/OmniCoreV3App.kt")
ui_ops = [
    (
        "import com.omnicore.emulator.storage.Ps1MediaLayout\nimport com.omnicore.emulator.storage.SafGameSource\n",
        "import com.omnicore.emulator.storage.Ps1MediaLayout\nimport com.omnicore.emulator.storage.Ps1PlaylistMedia\nimport com.omnicore.emulator.storage.SafGameSource\n",
        "playlist importer import",
    ),
    (
        '''    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n''',
        '''    fun importPs1Playlists(\n        playlists: List<SafGameSource.Document>,\n        documents: List<SafGameSource.Document>,\n        folderUri: String? = null,\n        sourceLabel: String\n    ): Boolean {\n        if (playlists.isEmpty()) return false\n        val additions = mutableListOf<GameEntry>()\n        playlists.forEach { playlist ->\n            val plan = Ps1PlaylistMedia.plan(context, playlist, documents).getOrElse { error ->\n                message = "${playlist.name}: ${error.message ?: "playlist multi-disc inválida"}"\n                return true\n            }\n            additions += GameEntry(\n                id = UUID.randomUUID().toString(),\n                title = playlist.name.substringBeforeLast('.', playlist.name),\n                fileName = playlist.name,\n                uri = playlist.uri.toString(),\n                system = ConsoleSystem.PLAYSTATION_1,\n                sizeBytes = plan.stagingDocuments.distinctBy { it.uri.toString() }.sumOf { it.sizeBytes },\n                folderUri = folderUri,\n                companionUris = if (folderUri == null) plan.stagingDocuments.map { it.uri.toString() } else emptyList()\n            )\n        }\n        persist(\n            additions,\n            "$sourceLabel: ${additions.size} jogo(s) multi-disc adicionado(s). A troca de disco ficará no Quick Menu durante a partida."\n        )\n        return true\n    }\n\n    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n''',
        "playlist import helper",
    ),
    (
        '''        val docs = uris.map { SafGameSource.metadata(context, it) }\n        val hasPs1Descriptor = docs.any { it.extension in Ps1Core.DESCRIPTOR_EXTENSIONS }\n''',
        '''        val docs = uris.map { SafGameSource.metadata(context, it) }\n        if (importPs1Playlists(docs.filter { it.extension == "m3u" }, docs, sourceLabel = "Seleção PS1")) {\n            return@rememberLauncherForActivityResult\n        }\n        val hasPs1Descriptor = docs.any { it.extension in Ps1Core.DESCRIPTOR_EXTENSIONS }\n''',
        "file-picker playlist priority",
    ),
    (
        '''        importPs1Plan(\n            plan = Ps1MediaLayout.plan(docs),\n            folderUri = treeUri.toString(),\n            sourceLabel = "Pasta PS1"\n        )\n''',
        '''        if (importPs1Playlists(\n                playlists = docs.filter { it.extension == "m3u" },\n                documents = docs,\n                folderUri = treeUri.toString(),\n                sourceLabel = "Pasta PS1"\n            )) {\n            return@rememberLauncherForActivityResult\n        }\n        importPs1Plan(\n            plan = Ps1MediaLayout.plan(docs),\n            folderUri = treeUri.toString(),\n            sourceLabel = "Pasta PS1"\n        )\n''',
        "folder-picker playlist priority",
    ),
]
patch_file(ui, ui_ops)

print("PS1 M3U import/runtime source patches applied successfully")
