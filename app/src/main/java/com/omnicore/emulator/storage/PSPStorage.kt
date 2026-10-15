package com.omnicore.emulator.storage

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/** Paths and PPSSPP assets are owned exclusively by the PSP runtime. */
object PSPStorage {
    data class Paths(
        val root: File,
        val roms: File,
        val saves: File,
        val states: File,
        val system: File,
        val cache: File
    )

    fun prepare(context: Context): Paths {
        val root = File(context.filesDir, "psp")
        val paths = Paths(
            root = root,
            roms = File(root, "roms"),
            saves = File(root, "saves"),
            states = File(root, "states"),
            system = File(root, "system"),
            cache = File(context.cacheDir, "psp")
        )
        listOf(paths.root, paths.roms, paths.saves, paths.states, paths.system, paths.cache).forEach {
            check(it.exists() || it.mkdirs()) { "Não foi possível preparar ${it.absolutePath}" }
        }
        installBundledAssets(context, File(paths.system, "PPSSPP"))
        return paths
    }

    private fun installBundledAssets(context: Context, destination: File) {
        val assetRoot = "psp/PPSSPP"
        val entries = runCatching { context.assets.list(assetRoot).orEmpty() }.getOrDefault(emptyArray())
        if (entries.isEmpty()) return

        val marker = File(destination, ".omnicore-assets-v1.20.4")
        if (marker.isFile) return
        if (destination.exists()) {
            check(destination.deleteRecursively()) {
                "Não foi possível atualizar os assets PPSSPP em ${destination.absolutePath}"
            }
        }
        check(destination.mkdirs() || destination.isDirectory) {
            "Não foi possível criar o diretório de assets PPSSPP."
        }
        entries.forEach { name -> copyAssetTree(context, "$assetRoot/$name", File(destination, name)) }
        marker.writeText("PPSSPP assets v1.20.4\n")
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = runCatching { context.assets.list(assetPath).orEmpty() }.getOrDefault(emptyArray())
        if (children.isNotEmpty()) {
            check(destination.isDirectory || destination.mkdirs()) {
                "Não foi possível criar ${destination.absolutePath}"
            }
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(destination, child))
            }
            return
        }

        val input = runCatching { context.assets.open(assetPath) }.getOrNull() ?: return
        destination.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Não foi possível criar ${parent.absolutePath}" }
        }
        BufferedInputStream(input, IO_BUFFER).use { source ->
            BufferedOutputStream(destination.outputStream(), IO_BUFFER).use { output ->
                source.copyTo(output, IO_BUFFER)
            }
        }
    }

    fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_')
        }
    }.ifBlank { "psp-game" }

    private const val IO_BUFFER = 64 * 1024
}
