package com.omnicore.emulator.core.psp

import android.view.Surface
import java.io.File
import kotlin.math.hypot

/** JNI boundary loaded only in the PSP-owned Android process. */
object PSPNativeBridge {
    private val runtimeLoaded: Boolean = runCatching {
        System.loadLibrary("omnicore_psp_runtime")
        true
    }.getOrDefault(false)

    @Volatile private var cachedCoreAvailable: Boolean? = null
    private val inputLock = Any()
    private val touchButtons = BooleanArray(16)
    private val physicalHolds = Array(16) { linkedSetOf<String>() }
    private val physicalAnalog = linkedMapOf<Int, Pair<Float, Float>>()
    private var touchAnalogX = 0f
    private var touchAnalogY = 0f

    fun hasCore(): Boolean {
        if (!runtimeLoaded) return false
        cachedCoreAvailable?.let { return it }
        return synchronized(this) {
            cachedCoreAvailable ?: runCatching { nativeHasCore() }
                .getOrDefault(false)
                .also { cachedCoreAvailable = it }
        }
    }

    fun runtimeInfo(): String =
        if (!runtimeLoaded) "Runtime PSP indisponível"
        else runCatching { nativeRuntimeInfo() }.getOrDefault("Runtime PSP carregado")

    fun start(surface: Surface, romPath: String, systemDir: String, saveDir: String): Boolean {
        if (!runtimeLoaded || !surface.isValid || !File(romPath).isFile) return false
        return runCatching {
            nativeStart(surface, romPath, systemDir, saveDir)
        }.getOrDefault(false)
    }

    fun stop() {
        if (runtimeLoaded) runCatching { nativeStop() }
    }

    fun setPaused(paused: Boolean) {
        if (runtimeLoaded) runCatching { nativeSetPaused(paused) }
    }

    fun isRunning(): Boolean =
        runtimeLoaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun lastMessage(): String =
        if (!runtimeLoaded) "Runtime PSP indisponível"
        else runCatching { nativeLastMessage() }.getOrDefault("Não foi possível ler o status do runtime PSP.")

    /** Touch-originated button state. Other input sources are OR-combined. */
    fun setButton(retroPadId: Int, pressed: Boolean) {
        if (!runtimeLoaded || retroPadId !in 0..15) return
        synchronized(inputLock) {
            touchButtons[retroPadId] = pressed
            publishButtonLocked(retroPadId)
        }
    }

    fun setPhysicalButton(retroPadId: Int, deviceId: Int, keyCode: Int, pressed: Boolean) {
        if (!runtimeLoaded || retroPadId !in 0..15) return
        synchronized(inputLock) {
            val token = "key:$deviceId:$keyCode"
            if (pressed) physicalHolds[retroPadId].add(token) else physicalHolds[retroPadId].remove(token)
            publishButtonLocked(retroPadId)
        }
    }

    fun setPhysicalHat(deviceId: Int, x: Float, y: Float) {
        if (!runtimeLoaded) return
        synchronized(inputLock) {
            setPhysicalHoldLocked(6, "hat:$deviceId:left", x < -0.5f)
            setPhysicalHoldLocked(7, "hat:$deviceId:right", x > 0.5f)
            setPhysicalHoldLocked(4, "hat:$deviceId:up", y < -0.5f)
            setPhysicalHoldLocked(5, "hat:$deviceId:down", y > 0.5f)
        }
    }

    fun setAnalog(x: Float, y: Float) {
        if (!runtimeLoaded) return
        synchronized(inputLock) {
            touchAnalogX = x.coerceIn(-1f, 1f)
            touchAnalogY = y.coerceIn(-1f, 1f)
            publishAnalogLocked()
        }
    }

    fun setPhysicalAnalog(deviceId: Int, x: Float, y: Float) {
        if (!runtimeLoaded) return
        synchronized(inputLock) {
            physicalAnalog[deviceId] = x.coerceIn(-1f, 1f) to y.coerceIn(-1f, 1f)
            publishAnalogLocked()
        }
    }

    fun releasePhysicalInput() {
        if (!runtimeLoaded) return
        synchronized(inputLock) {
            physicalHolds.forEach { it.clear() }
            physicalAnalog.clear()
            for (id in 0..15) publishButtonLocked(id)
            publishAnalogLocked()
        }
    }

    private fun setPhysicalHoldLocked(id: Int, token: String, pressed: Boolean) {
        if (pressed) physicalHolds[id].add(token) else physicalHolds[id].remove(token)
        publishButtonLocked(id)
    }

    private fun publishButtonLocked(id: Int) {
        nativeSetButton(id, touchButtons[id] || physicalHolds[id].isNotEmpty())
    }

    private fun publishAnalogLocked() {
        val active = physicalAnalog.values.firstOrNull { hypot(it.first, it.second) > 0.01f }
        val x = active?.first ?: touchAnalogX
        val y = active?.second ?: touchAnalogY
        nativeSetAnalog(x, y)
    }

    private external fun nativeHasCore(): Boolean
    private external fun nativeRuntimeInfo(): String
    private external fun nativeStart(surface: Surface, romPath: String, systemDir: String, saveDir: String): Boolean
    private external fun nativeStop()
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLastMessage(): String
    private external fun nativeSetButton(button: Int, pressed: Boolean)
    private external fun nativeSetAnalog(x: Float, y: Float)
}
