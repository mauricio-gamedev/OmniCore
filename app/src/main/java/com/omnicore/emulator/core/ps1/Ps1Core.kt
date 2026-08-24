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

        // Resolve SMART before the Activity exists so touch and physical controllers
        // receive the same per-game policy from their very first input event.
        val recommendation = Ps1ControlProfiles.recommend(game.title, game.fileName)
        InputSettings.applySmartAutoProfile(context, game.id, recommendation.mode)

        context.startActivity(EmulationActivity.intent(context, game, extension))
    }

    companion object {
        // Preserve the legacy standalone contract exactly. Descriptor-based media
        // is separate so the Ps1MediaLayout importer can keep CUE/BIN and
        // CCD/IMG/SUB grouped instead of exposing their tracks as duplicate games.
        val SINGLE_FILE_EXTENSIONS = setOf("chd", "pbp", "iso", "bin", "img", "mdf", "cbn", "exe")
        val DESCRIPTOR_EXTENSIONS = setOf("cue", "ccd")
        val SUPPORTED_EXTENSIONS = SINGLE_FILE_EXTENSIONS + DESCRIPTOR_EXTENSIONS
    }
}
