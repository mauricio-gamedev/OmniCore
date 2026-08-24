package com.omnicore.emulator.storage

import java.text.Normalizer

/**
 * Multi-disc/playlist planning for PS1.
 *
 * References are sanitized before Android staging. The runtime now exposes the
 * libretro disk-control interface, so validated M3U sets can remain one library
 * entry while the active disc is switched from the in-game Quick Menu.
 */
object Ps1DiscManifest {
    data class Disc(
        val reference: String,
        val label: String? = null,
        val discNumber: Int? = null
    )

    data class Manifest(
        val discs: List<Disc>,
        val warnings: List<String> = emptyList()
    ) {
        val isMultiDisc: Boolean get() = discs.size > 1
    }

    fun parseM3u(text: String): Manifest {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val discs = mutableListOf<Disc>()
        val warnings = mutableListOf<String>()
        var pendingLabel: String? = null

        normalized.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pendingLabel = line.substringAfter(',', "").trim().ifBlank { null }
                return@forEachIndexed
            }
            if (line.startsWith('#')) return@forEachIndexed

            val safe = sanitizeReference(line)
            if (safe == null) {
                warnings += "Linha ${index + 1}: referência de disco insegura ou externa foi ignorada."
                pendingLabel = null
                return@forEachIndexed
            }
            val extension = safe.substringAfterLast('.', "").lowercase()
            if (extension !in DISC_EXTENSIONS) {
                warnings += "Linha ${index + 1}: .$extension não é um formato de disco PS1 aceito no manifesto."
                pendingLabel = null
                return@forEachIndexed
            }
            discs += Disc(reference = safe, label = pendingLabel, discNumber = inferDiscNumber(safe))
            pendingLabel = null
        }

        if (discs.isEmpty()) warnings += "A playlist não contém nenhum disco PS1 utilizável."
        val duplicateRefs = discs.groupBy { it.reference.lowercase() }.filterValues { it.size > 1 }.keys
        if (duplicateRefs.isNotEmpty()) warnings += "A playlist repete ${duplicateRefs.size} referência(s) de disco."

        return Manifest(discs = discs, warnings = warnings.distinct())
    }

    /**
     * Conservative helper for folder auto-grouping. It does not mutate the
     * library; it only reports candidates that clearly share a base title and disc number.
     */
    fun groupByDiscNumber(fileNames: List<String>): Map<String, List<Disc>> {
        return fileNames.mapNotNull { name ->
            val number = inferDiscNumber(name) ?: return@mapNotNull null
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in DISC_EXTENSIONS) return@mapNotNull null
            val base = familyKey(name)
            base to Disc(reference = name, discNumber = number)
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, discs) -> discs.sortedBy { it.discNumber ?: Int.MAX_VALUE } }
            .filterValues { it.size > 1 }
    }

    fun sanitizeReference(value: String): String? {
        val trimmed = value.trim().trim('"')
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith('/') || trimmed.startsWith('\\')) return null
        if (SCHEME.matches(trimmed.substringBefore('/', trimmed))) return null

        val normalized = trimmed.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun familyKey(name: String): String {
        val noExt = name.substringBeforeLast('.', name)
        val stripped = DISC_TOKEN.replace(noExt, " ")
        val decomposed = Normalizer.normalize(stripped.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun inferDiscNumber(value: String): Int? {
        DISC_TOKEN.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        OF_TOKEN.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private val DISC_TOKEN = Regex("(?i)(?:disc|disk|cd)\\s*[-_ ]*0*([0-9]+)")
    private val OF_TOKEN = Regex("(?i)[(\\[]?\\s*0*([0-9]+)\\s*(?:of|de)\\s*0*[0-9]+\\s*[)\\]]?")
    private val SCHEME = Regex("(?i)^[a-z][a-z0-9+.-]*:$")
    val DISC_EXTENSIONS = setOf("cue", "ccd", "chd", "pbp", "iso", "bin", "img")
}
