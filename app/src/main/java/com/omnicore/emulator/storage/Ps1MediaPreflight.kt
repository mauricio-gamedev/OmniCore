package com.omnicore.emulator.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.omnicore.emulator.model.GameEntry
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight launch-time validation for PS1 single-file images.
 *
 * It reads only a small header and never rewrites/copies the game. Descriptor-based
 * formats such as CUE/CCD keep using the existing runtime validators.
 */
object Ps1MediaPreflight {
    data class Result(
        val ok: Boolean,
        val error: String? = null,
        val warnings: List<String> = emptyList()
    )

    private data class Probe(val bytes: ByteArray, val size: Long)

    fun validate(context: Context, game: GameEntry, extension: String): Result {
        val ext = extension.lowercase()
        if (ext !in HEADER_VALIDATED_EXTENSIONS && ext !in RAW_IMAGE_EXTENSIONS) return Result(ok = true)

        val probe = runCatching { probe(context, Uri.parse(game.uri), HEADER_BYTES) }
            .getOrElse { error ->
                return Result(
                    ok = false,
                    error = "Não consegui abrir ${game.fileName} para validação: ${error.message ?: "arquivo inacessível"}."
                )
            }

        if (probe.size == 0L || probe.bytes.isEmpty()) {
            return Result(ok = false, error = "${game.fileName} está vazio ou não forneceu bytes legíveis.")
        }

        return when (ext) {
            "chd" -> validateChd(game.fileName, probe)
            "pbp" -> validatePbp(game.fileName, probe)
            in RAW_IMAGE_EXTENSIONS -> validateRawImage(game.fileName, ext, probe)
            else -> Result(ok = true)
        }
    }

    private fun validateChd(name: String, probe: Probe): Result {
        val magic = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'r'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte())
        if (probe.bytes.size < magic.size || !probe.bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            return Result(ok = false, error = "$name não possui um cabeçalho CHD válido (MComprHD).")
        }
        if (probe.bytes.size >= 16) {
            val headerLength = ByteBuffer.wrap(probe.bytes, 8, 4).order(ByteOrder.BIG_ENDIAN).int
            val version = ByteBuffer.wrap(probe.bytes, 12, 4).order(ByteOrder.BIG_ENDIAN).int
            if (headerLength !in 64..4096 || version !in 1..5) {
                return Result(ok = false, error = "$name possui cabeçalho CHD inconsistente (versão $version, header $headerLength).")
            }
        }
        return Result(ok = true)
    }

    private fun validatePbp(name: String, probe: Probe): Result {
        if (probe.bytes.size < PBP_HEADER_BYTES) {
            return Result(ok = false, error = "$name é pequeno demais para conter um cabeçalho PBP completo.")
        }
        val b = probe.bytes
        val validMagic = b[0] == 0.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'B'.code.toByte() && b[3] == 'P'.code.toByte()
        if (!validMagic) return Result(ok = false, error = "$name não possui assinatura PBP válida.")

        val buffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val offsets = LongArray(8) { index -> buffer.getInt(8 + index * 4).toLong() and 0xffff_ffffL }
        if (offsets.any { it < PBP_HEADER_BYTES.toLong() }) {
            return Result(ok = false, error = "$name possui offsets PBP anteriores ao fim do cabeçalho.")
        }
        if (offsets.zipWithNext().any { (a, c) -> c < a }) {
            return Result(ok = false, error = "$name possui tabela de offsets PBP fora de ordem.")
        }
        val psarOffset = offsets.last()
        if (probe.size > 0L && psarOffset >= probe.size) {
            return Result(ok = false, error = "$name aponta DATA.PSAR para fora do arquivo.")
        }
        return Result(ok = true)
    }

    private fun validateRawImage(name: String, extension: String, probe: Probe): Result {
        if (probe.size in 1 until MIN_RAW_IMAGE_BYTES) {
            return Result(ok = false, error = "$name é pequeno demais para ser uma imagem de disco PS1 utilizável.")
        }
        if (probe.size <= 0L) return Result(ok = true)

        val commonSector = probe.size % 2352L == 0L || probe.size % 2048L == 0L || probe.size % 2336L == 0L
        if (!commonSector && extension in setOf("bin", "img", "iso")) {
            return Result(
                ok = true,
                warnings = listOf("$name não fecha em um tamanho de setor PS1 comum; o core ainda tentará abrir sem alterar o arquivo.")
            )
        }
        return Result(ok = true)
    }

    private fun probe(context: Context, uri: Uri, bytes: Int): Probe {
        val resolver = context.contentResolver
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor != null) {
            val size = descriptor.statSize
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(bytes)
                val count = BufferedInputStream(input).read(buffer)
                return Probe(if (count > 0) buffer.copyOf(count) else ByteArray(0), size)
            }
        }

        resolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "O Android não forneceu acesso de leitura." }
            val input = BufferedInputStream(raw)
            val buffer = ByteArray(bytes)
            val count = input.read(buffer)
            return Probe(if (count > 0) buffer.copyOf(count) else ByteArray(0), -1L)
        }
    }

    private const val HEADER_BYTES = 64
    private const val PBP_HEADER_BYTES = 40
    private const val MIN_RAW_IMAGE_BYTES = 64L * 1024L
    private val HEADER_VALIDATED_EXTENSIONS = setOf("chd", "pbp")
    private val RAW_IMAGE_EXTENSIONS = setOf("bin", "img", "iso")
}
