package com.omnicore.emulator.storage

/**
 * Pure planning layer for PS1 disc/media imports.
 *
 * This does not open files and does not change runtime behavior by itself. It exists
 * to keep descriptor-based sets (CUE/BIN and CCD/IMG/SUB) together and to prevent
 * sidecar tracks from being exposed as duplicate games when we wire it into the UI.
 */
object Ps1MediaLayout {
    enum class Kind { CUE_SET, CCD_SET, SINGLE }

    data class MediaSet(
        val primary: SafGameSource.Document,
        val companions: List<SafGameSource.Document>,
        val kind: Kind
    ) {
        val allDocuments: List<SafGameSource.Document>
            get() = (listOf(primary) + companions).distinctBy { it.uri.toString() }
    }

    data class Plan(
        val sets: List<MediaSet>,
        val warnings: List<String>
    )

    /**
     * Conservative folder/selection planning.
     *
     * CUE wins first because its FILE lines are authoritative and are validated later
     * by the existing runtime. CCD is then paired by basename with IMG and optional SUB.
     * Remaining standalone images are exposed only when they are not already claimed.
     */
    fun plan(documents: List<SafGameSource.Document>): Plan {
        val files = documents.filterNot { it.isDirectory }
        if (files.isEmpty()) return Plan(emptyList(), emptyList())

        val warnings = mutableListOf<String>()
        val sets = mutableListOf<MediaSet>()
        val claimed = mutableSetOf<String>()

        val cues = files.filter { it.extension == "cue" }
        if (cues.isNotEmpty()) {
            cues.forEach { cue ->
                sets += MediaSet(
                    primary = cue,
                    companions = files.filterNot { it.uri == cue.uri },
                    kind = Kind.CUE_SET
                )
                claimed += cue.uri.toString()
            }
            // Do not expose BIN/IMG tracks from the same selection as duplicate games.
            files.filter { it.extension in CUE_TRACK_EXTENSIONS }.forEach { claimed += it.uri.toString() }
        }

        val ccds = files.filter { it.extension == "ccd" }
        ccds.forEach { ccd ->
            val stem = stem(ccd.name)
            val matching = files.filter { stem(it.name).equals(stem, ignoreCase = true) }
            val image = matching.firstOrNull { it.extension == "img" }
            if (image == null) {
                warnings += "${ccd.name}: arquivo IMG correspondente não encontrado."
                return@forEach
            }
            val companions = matching.filter { it.uri != ccd.uri && it.extension in CCD_COMPANION_EXTENSIONS }
            sets += MediaSet(ccd, companions, Kind.CCD_SET)
            claimed += ccd.uri.toString()
            companions.forEach { claimed += it.uri.toString() }
        }

        val remaining = files.filter { file ->
            file.uri.toString() !in claimed && file.extension in STANDALONE_EXTENSIONS
        }

        if (cues.isEmpty() && ccds.isEmpty() && remaining.count { it.extension == "bin" } > 1) {
            warnings += "Há vários BIN sem CUE. A ordem das faixas fica ambígua sem um descritor."
        }

        remaining.forEach { file ->
            sets += MediaSet(file, emptyList(), Kind.SINGLE)
        }

        return Plan(sets.distinctBy { it.primary.uri.toString() }, warnings.distinct())
    }

    private fun stem(name: String): String = name.substringBeforeLast('.', name)

    val STANDALONE_EXTENSIONS = setOf("chd", "pbp", "iso", "bin", "img", "mdf", "cbn", "exe")
    val CUE_TRACK_EXTENSIONS = setOf("bin", "img", "wav", "ape", "flac", "sbi")
    val CCD_COMPANION_EXTENSIONS = setOf("img", "sub", "sbi")
}
