package com.omnicore.emulator.storage

import android.content.Context
import android.net.Uri

/**
 * Resolves an M3U playlist against Android SAF documents without changing the
 * existing CUE/CCD/single-file paths. The first implementation intentionally
 * accepts only direct sibling references; nested playlist paths remain rejected
 * until the SAF importer grows recursive directory traversal.
 */
object Ps1PlaylistMedia {
    data class Plan(
        val manifest: Ps1DiscManifest.Manifest,
        val discDocuments: List<SafGameSource.Document>,
        val stagingDocuments: List<SafGameSource.Document>,
        val warnings: List<String>
    )

    fun plan(
        context: Context,
        playlist: SafGameSource.Document,
        documents: List<SafGameSource.Document>
    ): Result<Plan> = runCatching {
        require(playlist.extension == "m3u") { "O arquivo selecionado não é uma playlist M3U." }
        val text = readText(context, playlist.uri)
        val manifest = Ps1DiscManifest.parseM3u(text)
        require(manifest.discs.size >= 2) {
            manifest.warnings.firstOrNull() ?: "A playlist precisa listar pelo menos dois discos."
        }

        val files = documents.filterNot { it.isDirectory }
        val byName = files.associateBy { it.name.lowercase() }
        val resolved = manifest.discs.map { disc ->
            require('/' !in disc.reference && '\\' !in disc.reference) {
                "A referência '${disc.reference}' usa subpasta. Nesta etapa, mantenha o M3U e os discos na mesma pasta."
            }
            byName[disc.reference.lowercase()]
                ?: error("O disco '${disc.reference}' listado no M3U não foi encontrado na mesma seleção/pasta.")
        }

        val staged = linkedMapOf<String, SafGameSource.Document>()
        fun add(document: SafGameSource.Document) {
            staged.putIfAbsent(document.uri.toString(), document)
        }
        add(playlist)
        resolved.forEach { disc ->
            add(disc)
            when (disc.extension) {
                "cue" -> {
                    val cueText = SafGameSource.readCueText(context, disc.uri)
                    val refs = SafGameSource.cueReferences(cueText)
                    require(refs.isNotEmpty()) { "${disc.name} não contém linhas FILE reconhecíveis." }
                    refs.forEach { ref ->
                        val name = SafGameSource.normalizeReference(ref)
                        val track = byName[name.lowercase()]
                            ?: error("Faixa '$name' citada por ${disc.name} não encontrada ao lado da playlist.")
                        add(track)
                    }
                    val stem = stem(disc.name)
                    files.filter { stem(it.name).equals(stem, ignoreCase = true) && it.extension == "sbi" }.forEach(::add)
                }
                "ccd" -> {
                    val stem = stem(disc.name)
                    val siblings = files.filter { stem(it.name).equals(stem, ignoreCase = true) }
                    require(siblings.any { it.extension == "img" }) {
                        "${disc.name} precisa do IMG correspondente na mesma pasta."
                    }
                    siblings.filter { it.extension in setOf("img", "sub", "sbi") }.forEach(::add)
                }
                else -> {
                    val stem = stem(disc.name)
                    files.filter { stem(it.name).equals(stem, ignoreCase = true) && it.extension == "sbi" }.forEach(::add)
                }
            }
        }

        Plan(
            manifest = manifest,
            discDocuments = resolved,
            stagingDocuments = staged.values.toList(),
            warnings = manifest.warnings
        )
    }

    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "O Android não forneceu acesso à playlist." }
            val bytes = input.readBytes(MAX_PLAYLIST_BYTES + 1)
            require(bytes.size <= MAX_PLAYLIST_BYTES) { "A playlist M3U é grande demais para ser válida." }
            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }

    private fun stem(name: String): String = name.substringBeforeLast('.', name)
    private const val MAX_PLAYLIST_BYTES = 256 * 1024
}
