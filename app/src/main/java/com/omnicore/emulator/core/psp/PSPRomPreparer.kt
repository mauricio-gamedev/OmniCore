package com.omnicore.emulator.core.psp

import android.content.Context
import android.net.Uri
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.storage.PSPStorage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/** Copies SAF-backed PSP images into a PSP-only cache; original files are never changed. */
object PSPRomPreparer {
    fun prepare(context: Context, game: GameEntry, paths: PSPStorage.Paths): Result<File> = runCatching {
        val extension = game.fileName.substringAfterLast('.', "").lowercase()
        require(extension in PSPCore.SUPPORTED_EXTENSIONS) {
            "Formato PSP não suportado: .$extension"
        }
        val key = PSPStorage.safeGameKey(game.id)
        val target = File(paths.roms, "$key.$extension")
        val metadata = File(paths.roms, "$key.meta")
        val fingerprint = listOf(game.uri, game.fileName, game.sizeBytes.toString()).joinToString("\n")

        if (target.isFile && target.length() > 0L && metadata.isFile && metadata.readText() == fingerprint) {
            return@runCatching target
        }

        val temporary = File(paths.roms, "$key.$extension.tmp")
        temporary.delete()
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(game.uri))
                ?: error("Não foi possível abrir a imagem PSP. Selecione o arquivo novamente.")
            BufferedInputStream(input, IO_BUFFER).use { source ->
                BufferedOutputStream(temporary.outputStream(), IO_BUFFER).use { output ->
                    val buffer = ByteArray(IO_BUFFER)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= MAX_IMAGE_BYTES) {
                            "A imagem PSP excede o limite de segurança de 4 GiB."
                        }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0L) { "A imagem PSP está vazia." }
                    if (game.sizeBytes > 0L) {
                        require(total == game.sizeBytes) {
                            "A imagem PSP mudou durante a cópia (${total} de ${game.sizeBytes} bytes)."
                        }
                    }
                }
            }

            if (target.exists()) check(target.delete()) { "Não foi possível substituir o cache PSP." }
            check(temporary.renameTo(target) || runCatching {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
                true
            }.getOrDefault(false)) { "Não foi possível concluir o cache PSP." }
            metadata.writeText(fingerprint)
            target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private const val IO_BUFFER = 64 * 1024
    private const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L * 1024L
}
