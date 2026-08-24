package com.omnicore.emulator.core.ps1

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.emulation.EmulationActivity
import com.omnicore.emulator.emulation.Ps1ControlProfiles
import com.omnicore.emulator.library.RomDetector
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.settings.InputSettings
import com.omnicore.emulator.settings.Ps1Settings
import com.omnicore.emulator.storage.Ps1MediaPreflight

class Ps1Core : EmulatorCore {
    override val info = CoreInfo(
        id = "pcsx-rearmed",
        name = "PCSX-ReARMed",
        system = ConsoleSystem.PLAYSTATION_1,
        state = CoreState.READY,
        version = "pinned da2cb8e"
    )

    override fun isAvailable(): Boolean = NativeBridge.hasPs1Core()

    override fun launch(context: Context, game: GameEntry): Result<Unit> = runCatching {
        check(isAvailable()) {
            "O core PS1 não está empacotado neste APK. Gere o build completo pelo workflow Android Build."
        }
        val extension = RomDetector.extension(game.fileName)
        require(extension in SUPPORTED_EXTENSIONS) {
            "Formato PS1 não suportado: .$extension"
        }
        if (extension == "cue") {
            require(!game.folderUri.isNullOrBlank() || game.companionUris.size > 1) {
                "Este CUE precisa das faixas BIN. Importe a pasta do jogo ou selecione CUE + BIN juntos."
            }
        }
        if (extension == "ccd") {
            require(!game.folderUri.isNullOrBlank() || game.companionUris.isNotEmpty()) {
                "Este CCD precisa do IMG correspondente. Importe a pasta completa do jogo para manter CCD/IMG/SUB juntos."
            }
        }
        if (extension == "m3u") {
            require(!game.folderUri.isNullOrBlank() || game.companionUris.size > 2) {
                "Esta playlist M3U precisa dos discos e arquivos auxiliares. Importe a pasta completa ou selecione a playlist com todos os discos."
            }
        }

        // Compatibility 2.0 preflight is header-only and does not rewrite media.
        // Descriptor sets keep their existing CUE/CCD/M3U runtime validation paths.
        val preflight = Ps1MediaPreflight.validate(context, game, extension)
        require(preflight.ok) {
            preflight.error ?: "A imagem de PS1 falhou na validação antes do boot."
        }

        // Resolve SMART before the Activity exists so touch and physical controllers
        // receive the same per-game policy from their very first input event. Native
        // recommendations are gated by the actual emulated pad type.
        val recommendation = Ps1ControlProfiles.recommend(
            title = game.title,
            fileName = game.fileName,
            dualShockEnabled = Ps1Settings.resolve(context).dualShock
        )
        InputSettings.applySmartAutoProfile(context, game.id, recommendation.mode)

        context.startActivity(EmulationActivity.intent(context, game, extension))
    }

    companion object {
        // Preserve the legacy standalone contract exactly. Descriptor-based media
        // is separate so import planning can keep CUE/BIN, CCD/IMG/SUB and M3U
        // multi-disc sets grouped instead of exposing their tracks as duplicate games.
        val SINGLE_FILE_EXTENSIONS = setOf("chd", "pbp", "iso", "bin", "img", "mdf", "cbn", "exe")
        val DESCRIPTOR_EXTENSIONS = setOf("cue", "ccd", "m3u")
        val SUPPORTED_EXTENSIONS = SINGLE_FILE_EXTENSIONS + DESCRIPTOR_EXTENSIONS
    }
}
