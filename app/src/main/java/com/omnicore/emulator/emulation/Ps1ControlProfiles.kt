package com.omnicore.emulator.emulation

import com.omnicore.emulator.settings.InputSettings
import java.text.Normalizer

/**
 * Conservative PS1 control-profile resolver used only while the user-facing mode is SMART.
 *
 * This is deliberately separate from the touch/physical input engines. A bad database rule
 * must never change core timing or button plumbing; it can only select one of the already
 * supported input modes. Unknown titles keep the legacy SMART hybrid fallback.
 */
object Ps1ControlProfiles {
    enum class Confidence { HIGH, FALLBACK }

    data class Recommendation(
        val mode: InputSettings.AnalogMode,
        val confidence: Confidence,
        val family: String
    )

    fun recommend(title: String, fileName: String = ""): Recommendation {
        val identity = normalize("$title $fileName")

        TANK_FAMILIES.firstOrNull { rule -> rule.tokens.any(identity::contains) }?.let { rule ->
            return Recommendation(
                mode = InputSettings.AnalogMode.TANK_ASSIST,
                confidence = Confidence.HIGH,
                family = rule.name
            )
        }

        NATIVE_FAMILIES.firstOrNull { rule -> rule.tokens.any(identity::contains) }?.let { rule ->
            return Recommendation(
                mode = InputSettings.AnalogMode.NATIVE,
                confidence = Confidence.HIGH,
                family = rule.name
            )
        }

        // Preserve the old SMART behavior for everything we cannot identify with high
        // confidence. This is the compatibility floor and prevents a growing database
        // from silently changing controls in unrelated games.
        return Recommendation(
            mode = InputSettings.AnalogMode.SMART,
            confidence = Confidence.FALLBACK,
            family = "legacy-hybrid"
        )
    }

    private data class FamilyRule(val name: String, val tokens: List<String>)

    private val TANK_FAMILIES = listOf(
        FamilyRule("resident-evil", listOf("resident evil", "biohazard")),
        FamilyRule("silent-hill", listOf("silent hill")),
        FamilyRule("dino-crisis", listOf("dino crisis")),
        FamilyRule("tomb-raider", listOf("tomb raider")),
        FamilyRule("parasite-eve-2", listOf("parasite eve 2", "parasite eve ii")),
        FamilyRule("galerians", listOf("galerians")),
        FamilyRule("alone-in-the-dark", listOf("alone in the dark")),
        FamilyRule("countdown-vampires", listOf("countdown vampires")),
        FamilyRule("martian-gothic", listOf("martian gothic")),
        FamilyRule("chaos-break", listOf("chaos break"))
    )

    // Keep this list intentionally strict: Native means we stop projecting the left
    // stick to the D-pad. Ape Escape is a safe first family because DualShock analog
    // control is fundamental to its control scheme.
    private val NATIVE_FAMILIES = listOf(
        FamilyRule("ape-escape", listOf("ape escape", "saru get you", "sarugetchu"))
    )

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
