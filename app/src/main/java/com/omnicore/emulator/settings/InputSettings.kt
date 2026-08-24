package com.omnicore.emulator.settings

import android.content.Context

object InputSettings {
    enum class AnalogMode(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Auto por jogo; desconhecidos mantêm o híbrido analógico + D-pad"),
        NATIVE("native", "Nativo", "Envia somente eixos analógicos DualShock"),
        DPAD("dpad", "D-pad", "Stick touch funciona como direcional digital"),
        TANK_ASSIST("tank_assist", "Movimento moderno", "Stick vira intenção de direção para jogos com controle tank")
    }

    enum class OverlayPreset(val storage: String, val label: String, val subtitle: String) {
        CLEAN("clean", "Limpo", "Pouca informação na tela e controles discretos"),
        COMPACT("compact", "Compacto", "Controles menores e próximos das bordas"),
        STANDARD("standard", "Padrão", "Layout clássico com labels visíveis"),
        LEFT("left", "Mão esquerda", "Ações aproximadas do lado esquerdo"),
        RIGHT("right", "Mão direita", "Analógico aproximado do lado direito"),
        TABLET("tablet", "Tablet", "Controles espalhados para telas maiores")
    }

    data class Config(
        val analogMode: AnalogMode,
        val touchOpacity: Float,
        val touchScale: Float,
        val haptics: Boolean,
        val showDpad: Boolean,
        val overlayPreset: OverlayPreset,
        val cleanOverlay: Boolean,
        val dynamicOpacity: Boolean,
        val showLabels: Boolean,
        val showShoulders: Boolean,
        val showStartSelect: Boolean,
        val showPerformanceHud: Boolean,
        val controlsVisible: Boolean
    )

    data class ControlPosition(val x: Float, val y: Float)

    private const val PREFS = "input_settings"
    private const val KEY_ANALOG_MODE = "analog_mode"
    private const val KEY_AUTO_ANALOG = "auto_analog_profile"
    private const val KEY_MANUAL_ANALOG = "manual_analog_profile"
    private const val KEY_TOUCH_OPACITY = "touch_opacity"
    private const val KEY_TOUCH_SCALE = "touch_scale"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_SHOW_DPAD = "show_dpad"
    private const val KEY_OVERLAY_PRESET = "overlay_preset"
    private const val KEY_CLEAN_OVERLAY = "clean_overlay"
    private const val KEY_DYNAMIC_OPACITY = "dynamic_opacity"
    private const val KEY_SHOW_LABELS = "show_labels"
    private const val KEY_SHOW_SHOULDERS = "show_shoulders"
    private const val KEY_SHOW_START_SELECT = "show_start_select"
    private const val KEY_SHOW_PERFORMANCE_HUD = "show_performance_hud"
    private const val KEY_CONTROLS_VISIBLE = "controls_visible"
    private const val POSITION_PREFIX = "control_position_"
    private const val SCALE_PREFIX = "control_scale_"
    private const val GAME_PREFIX = "game_"

    fun resolve(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modeRaw = prefs.getString(KEY_ANALOG_MODE, AnalogMode.SMART.storage)
        val analogMode = AnalogMode.entries.firstOrNull { it.storage == modeRaw } ?: AnalogMode.SMART
        val presetRaw = prefs.getString(KEY_OVERLAY_PRESET, OverlayPreset.CLEAN.storage)
        val preset = OverlayPreset.entries.firstOrNull { it.storage == presetRaw } ?: OverlayPreset.CLEAN
        val defaultShowDpad = analogMode == AnalogMode.DPAD
        return Config(
            analogMode = analogMode,
            touchOpacity = prefs.getFloat(KEY_TOUCH_OPACITY, 0.78f).coerceIn(0.35f, 1f),
            touchScale = prefs.getFloat(KEY_TOUCH_SCALE, 1f).coerceIn(0.80f, 1.20f),
            haptics = prefs.getBoolean(KEY_HAPTICS, false),
            showDpad = if (prefs.contains(KEY_SHOW_DPAD)) prefs.getBoolean(KEY_SHOW_DPAD, defaultShowDpad) else defaultShowDpad,
            overlayPreset = preset,
            cleanOverlay = prefs.getBoolean(KEY_CLEAN_OVERLAY, preset != OverlayPreset.STANDARD),
            dynamicOpacity = prefs.getBoolean(KEY_DYNAMIC_OPACITY, true),
            showLabels = prefs.getBoolean(KEY_SHOW_LABELS, preset == OverlayPreset.STANDARD),
            showShoulders = prefs.getBoolean(KEY_SHOW_SHOULDERS, true),
            showStartSelect = prefs.getBoolean(KEY_SHOW_START_SELECT, true),
            showPerformanceHud = prefs.getBoolean(KEY_SHOW_PERFORMANCE_HUD, false),
            controlsVisible = prefs.getBoolean(KEY_CONTROLS_VISIBLE, true)
        )
    }

    fun resolveForGame(context: Context, gameKey: String): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = resolve(context)
        val prefix = gamePrefix(gameKey)
        if (!prefs.all.keys.any { it.startsWith(prefix) }) return base

        fun string(key: String, fallback: String): String = prefs.getString(prefix + key, fallback) ?: fallback
        fun bool(key: String, fallback: Boolean): Boolean = if (prefs.contains(prefix + key)) prefs.getBoolean(prefix + key, fallback) else fallback
        fun number(key: String, fallback: Float): Float = if (prefs.contains(prefix + key)) prefs.getFloat(prefix + key, fallback) else fallback

        val analog = AnalogMode.entries.firstOrNull { it.storage == string(KEY_ANALOG_MODE, base.analogMode.storage) } ?: base.analogMode
        val preset = OverlayPreset.entries.firstOrNull { it.storage == string(KEY_OVERLAY_PRESET, base.overlayPreset.storage) } ?: base.overlayPreset
        return base.copy(
            analogMode = analog,
            touchOpacity = number(KEY_TOUCH_OPACITY, base.touchOpacity).coerceIn(0.35f, 1f),
            touchScale = number(KEY_TOUCH_SCALE, base.touchScale).coerceIn(0.80f, 1.20f),
            haptics = bool(KEY_HAPTICS, base.haptics),
            showDpad = bool(KEY_SHOW_DPAD, base.showDpad),
            overlayPreset = preset,
            cleanOverlay = bool(KEY_CLEAN_OVERLAY, base.cleanOverlay),
            dynamicOpacity = bool(KEY_DYNAMIC_OPACITY, base.dynamicOpacity),
            showLabels = bool(KEY_SHOW_LABELS, base.showLabels),
            showShoulders = bool(KEY_SHOW_SHOULDERS, base.showShoulders),
            showStartSelect = bool(KEY_SHOW_START_SELECT, base.showStartSelect),
            showPerformanceHud = bool(KEY_SHOW_PERFORMANCE_HUD, base.showPerformanceHud),
            controlsVisible = bool(KEY_CONTROLS_VISIBLE, base.controlsVisible)
        )
    }

    /**
     * Persists only visual/layout state. Control ownership is intentionally handled by
     * saveGameAnalogMode/clearGameAnalogMode so changing opacity or layout cannot freeze
     * a stale analog mode and block SMART Auto on the next launch.
     */
    fun saveGameConfig(context: Context, gameKey: String, config: Config) {
        val prefix = gamePrefix(gameKey)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(prefix + KEY_TOUCH_OPACITY, config.touchOpacity.coerceIn(0.35f, 1f))
            .putFloat(prefix + KEY_TOUCH_SCALE, config.touchScale.coerceIn(0.80f, 1.20f))
            .putBoolean(prefix + KEY_HAPTICS, config.haptics)
            .putBoolean(prefix + KEY_SHOW_DPAD, config.showDpad)
            .putString(prefix + KEY_OVERLAY_PRESET, config.overlayPreset.storage)
            .putBoolean(prefix + KEY_CLEAN_OVERLAY, config.cleanOverlay)
            .putBoolean(prefix + KEY_DYNAMIC_OPACITY, config.dynamicOpacity)
            .putBoolean(prefix + KEY_SHOW_LABELS, config.showLabels)
            .putBoolean(prefix + KEY_SHOW_SHOULDERS, config.showShoulders)
            .putBoolean(prefix + KEY_SHOW_START_SELECT, config.showStartSelect)
            .putBoolean(prefix + KEY_SHOW_PERFORMANCE_HUD, config.showPerformanceHud)
            .putBoolean(prefix + KEY_CONTROLS_VISIBLE, config.controlsVisible)
            .apply()
    }

    fun clearGameProfile(context: Context, gameKey: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gamePrefix(gameKey)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    /**
     * Applies a SMART recommendation while keeping explicit Input 2.0 manual choices
     * authoritative. Alpha 6 did not have a manual-ownership marker and its visual
     * editor used to write analog_mode as a side effect, so stale legacy values are
     * migrated back under SMART ownership once. After the user explicitly chooses a
     * per-game mode in Input 2.0, KEY_MANUAL_ANALOG prevents future auto overrides.
     */
    fun applySmartAutoProfile(context: Context, gameKey: String, recommendation: AnalogMode) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gamePrefix(gameKey)
        val modeKey = prefix + KEY_ANALOG_MODE
        val autoKey = prefix + KEY_AUTO_ANALOG
        val manualKey = prefix + KEY_MANUAL_ANALOG
        val globalMode = resolve(context).analogMode
        val autoOwned = prefs.getBoolean(autoKey, false)
        val manualOwned = prefs.getBoolean(manualKey, false)

        if (globalMode != AnalogMode.SMART) {
            if (autoOwned) prefs.edit().remove(modeKey).remove(autoKey).apply()
            return
        }

        if (manualOwned) return

        if (recommendation == AnalogMode.SMART) {
            if (autoOwned) prefs.edit().remove(modeKey).remove(autoKey).apply()
            return
        }

        // SMART owns known-game recommendations. This also repairs stale Alpha 6
        // per-game analog values that were written merely by editing visual settings.
        prefs.edit()
            .putString(modeKey, recommendation.storage)
            .putBoolean(autoKey, true)
            .remove(manualKey)
            .apply()
    }

    fun saveAnalogMode(context: Context, mode: AnalogMode) { edit(context).putString(KEY_ANALOG_MODE, mode.storage).apply() }

    fun saveGameAnalogMode(context: Context, gameKey: String, mode: AnalogMode) {
        val prefix = gamePrefix(gameKey)
        edit(context)
            .putString(prefix + KEY_ANALOG_MODE, mode.storage)
            .putBoolean(prefix + KEY_MANUAL_ANALOG, true)
            .remove(prefix + KEY_AUTO_ANALOG)
            .apply()
    }

    fun clearGameAnalogMode(context: Context, gameKey: String) {
        val prefix = gamePrefix(gameKey)
        edit(context)
            .remove(prefix + KEY_ANALOG_MODE)
            .remove(prefix + KEY_AUTO_ANALOG)
            .remove(prefix + KEY_MANUAL_ANALOG)
            .apply()
    }

    fun saveTouchOpacity(context: Context, value: Float) { edit(context).putFloat(KEY_TOUCH_OPACITY, value.coerceIn(0.35f, 1f)).apply() }
    fun saveTouchScale(context: Context, value: Float) { edit(context).putFloat(KEY_TOUCH_SCALE, value.coerceIn(0.80f, 1.20f)).apply() }
    fun saveHaptics(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_HAPTICS, enabled).apply() }
    fun saveShowDpad(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_SHOW_DPAD, enabled).apply() }
    fun saveOverlayPreset(context: Context, preset: OverlayPreset) { edit(context).putString(KEY_OVERLAY_PRESET, preset.storage).apply() }
    fun saveCleanOverlay(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_CLEAN_OVERLAY, enabled).apply() }
    fun saveDynamicOpacity(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_DYNAMIC_OPACITY, enabled).apply() }
    fun saveShowLabels(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_SHOW_LABELS, enabled).apply() }
    fun saveShowShoulders(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_SHOW_SHOULDERS, enabled).apply() }
    fun saveShowStartSelect(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_SHOW_START_SELECT, enabled).apply() }
    fun saveShowPerformanceHud(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_SHOW_PERFORMANCE_HUD, enabled).apply() }
    fun saveControlsVisible(context: Context, enabled: Boolean) { edit(context).putBoolean(KEY_CONTROLS_VISIBLE, enabled).apply() }

    fun resolveControlPosition(
        context: Context,
        key: String,
        defaultX: Float,
        defaultY: Float,
        gameKey: String? = null
    ): ControlPosition {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val globalX = "${POSITION_PREFIX}${key}_x"
        val globalY = "${POSITION_PREFIX}${key}_y"
        val gamePrefix = gameKey?.let(::gamePrefix)
        val xKey = gamePrefix?.let { it + globalX }
        val yKey = gamePrefix?.let { it + globalY }
        val x = when {
            xKey != null && prefs.contains(xKey) -> prefs.getFloat(xKey, defaultX)
            prefs.contains(globalX) -> prefs.getFloat(globalX, defaultX)
            else -> defaultX
        }.coerceIn(0.04f, 0.96f)
        val y = when {
            yKey != null && prefs.contains(yKey) -> prefs.getFloat(yKey, defaultY)
            prefs.contains(globalY) -> prefs.getFloat(globalY, defaultY)
            else -> defaultY
        }.coerceIn(0.06f, 0.95f)
        return ControlPosition(x, y)
    }

    fun saveControlPosition(context: Context, key: String, x: Float, y: Float, gameKey: String? = null) {
        val prefix = gameKey?.let(::gamePrefix).orEmpty()
        edit(context)
            .putFloat("$prefix${POSITION_PREFIX}${key}_x", x.coerceIn(0.04f, 0.96f))
            .putFloat("$prefix${POSITION_PREFIX}${key}_y", y.coerceIn(0.06f, 0.95f))
            .apply()
    }

    fun resolveControlScale(context: Context, key: String, gameKey: String? = null): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val globalKey = SCALE_PREFIX + key
        val gameScaleKey = gameKey?.let { gamePrefix(it) + globalKey }
        return when {
            gameScaleKey != null && prefs.contains(gameScaleKey) -> prefs.getFloat(gameScaleKey, 1f)
            prefs.contains(globalKey) -> prefs.getFloat(globalKey, 1f)
            else -> 1f
        }.coerceIn(0.65f, 1.45f)
    }

    fun saveControlScale(context: Context, key: String, value: Float, gameKey: String? = null) {
        val prefix = gameKey?.let(::gamePrefix).orEmpty()
        edit(context).putFloat("$prefix$SCALE_PREFIX$key", value.coerceIn(0.65f, 1.45f)).apply()
    }

    fun resetControlScales(context: Context, gameKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gameKey?.let { gamePrefix(it) + SCALE_PREFIX } ?: SCALE_PREFIX
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    fun resetControlPositions(context: Context, gameKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = gameKey?.let { gamePrefix(it) + POSITION_PREFIX } ?: POSITION_PREFIX
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    private fun edit(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()

    private fun gamePrefix(value: String): String = GAME_PREFIX + safeGameKey(value) + "_"

    private fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "game" }
}
