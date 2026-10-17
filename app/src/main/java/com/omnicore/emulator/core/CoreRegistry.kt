package com.omnicore.emulator.core

import android.content.Context
import com.omnicore.emulator.core.n64.N64Core
import com.omnicore.emulator.core.ps1.Ps1Core
import com.omnicore.emulator.core.ps2.PS2Core
import com.omnicore.emulator.core.psp.PSPCore
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry

private class PlannedCore(
    override val info: CoreInfo
) : EmulatorCore {
    override fun isAvailable() = false

    override fun launch(context: Context, game: GameEntry): Result<Unit> =
        Result.failure(IllegalStateException("${info.name} ainda não foi integrado."))
}

object CoreRegistry {
    private val cores: List<EmulatorCore> = ConsoleSystem.entries.map { system ->
        when (system) {
            ConsoleSystem.PLAYSTATION_1 -> Ps1Core()
            ConsoleSystem.NINTENDO_64 -> N64Core()
            ConsoleSystem.PLAYSTATION_2 -> PS2Core()
            ConsoleSystem.PSP -> PSPCore()
            else -> PlannedCore(
                CoreInfo(
                    id = "${system.shortName.lowercase()}-core",
                    name = "${system.displayName} Core",
                    system = system,
                    state = CoreState.PLANNED
                )
            )
        }
    }

    fun all(): List<CoreInfo> = cores.map { it.info }
    fun forSystem(system: ConsoleSystem): EmulatorCore? = cores.firstOrNull { it.info.system == system }
}
