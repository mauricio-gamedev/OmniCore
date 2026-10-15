package com.omnicore.emulator.core.psp

import android.content.Context
import com.omnicore.emulator.core.CoreInfo
import com.omnicore.emulator.core.CoreState
import com.omnicore.emulator.core.EmulatorCore
import com.omnicore.emulator.emulation.PSPEmulationActivity
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry

/** PPSSPP integration; the PSP native boundary is loaded only after entering :psp. */
class PSPCore : EmulatorCore {
    override val info = CoreInfo(
        id = "ppsspp",
        name = "PPSSPP",
        system = ConsoleSystem.PSP,
        state = CoreState.EXPERIMENTAL,
        version = "pinned v1.20.4"
    )

    // Do not load PSP's native renderer in the library hub's main process.
    override fun isAvailable(): Boolean = true

    override fun launch(context: Context, game: GameEntry): Result<Unit> = runCatching {
        require(game.system == ConsoleSystem.PSP) { "Este jogo não está classificado como PSP." }
        val extension = game.fileName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "Formato PSP não reconhecido: .$extension. Use ISO, CSO ou PBP."
        }
        context.startActivity(PSPEmulationActivity.intent(context, game))
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("iso", "cso", "pbp")
    }
}
