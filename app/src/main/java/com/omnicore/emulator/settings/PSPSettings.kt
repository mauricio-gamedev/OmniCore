package com.omnicore.emulator.settings

import android.content.Context

/** PSP-only persisted preferences; never shared with other console runtimes. */
object PSPSettings {
    private const val PREFS = "psp_settings"
    private const val KEY_INTERNAL_RESOLUTION = "internal_resolution"
    private const val KEY_CONTROL_OPACITY = "control_opacity"
    private const val KEY_CONTROL_SIZE = "control_size"
    private const val KEY_SMART_OPACITY = "smart_opacity"
    private const val KEY_RENDERER = "renderer"

    enum class Renderer(val storage: String, val label: String) {
        AUTO("auto", "Automático"),
        OPENGL("opengl", "OpenGL ES"),
        VULKAN("vulkan", "Vulkan (experimental)")
    }

    fun internalResolution(context: Context, fallback: Int): Int =
        prefs(context).getInt(KEY_INTERNAL_RESOLUTION, fallback).coerceIn(1, 2)

    fun saveInternalResolution(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_INTERNAL_RESOLUTION, value.coerceIn(1, 2)).apply()
    }

    fun controlOpacity(context: Context): Int =
        prefs(context).getInt(KEY_CONTROL_OPACITY, 72).coerceIn(20, 100)

    fun saveControlOpacity(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_CONTROL_OPACITY, value.coerceIn(20, 100)).apply()
    }

    fun controlSize(context: Context): Int =
        prefs(context).getInt(KEY_CONTROL_SIZE, 100).coerceIn(70, 140)

    fun saveControlSize(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_CONTROL_SIZE, value.coerceIn(70, 140)).apply()
    }

    fun smartOpacity(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_OPACITY, true)

    fun saveSmartOpacity(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_OPACITY, enabled).apply()
    }

    fun renderer(context: Context): Renderer {
        val raw = prefs(context).getString(KEY_RENDERER, Renderer.AUTO.storage)
        return Renderer.entries.firstOrNull { it.storage == raw } ?: Renderer.AUTO
    }

    fun saveRenderer(context: Context, renderer: Renderer) {
        prefs(context).edit().putString(KEY_RENDERER, renderer.storage).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
