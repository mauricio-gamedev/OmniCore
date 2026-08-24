package com.omnicore.emulator.core.nativebridge

import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object NativeBridge {
    data class DiskState(
        val count: Int,
        val index: Int,
        val ejected: Boolean
    ) {
        val available: Boolean get() = count > 1
    }

    private val loaded: Boolean = runCatching {
        System.loadLibrary("omnicore_runtime")
        true
    }.getOrDefault(false)

    private val coreAvailabilityLock = Any()
    @Volatile private var ps1CoreAvailable: Boolean? = null

    private val stateLoadGeneration = AtomicInteger(0)
    private val stateLoadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OmniCore-StatePrefetch").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    @Volatile private var activeGameKey: String = ""
    @Volatile private var activeStateDir: String = ""

    fun isLoaded(): Boolean = loaded

    fun runtimeVersion(): String =
        if (loaded) runCatching { nativeRuntimeVersion() }.getOrDefault("native-runtime-error")
        else "native-runtime-unavailable"

    fun hasPs1Core(): Boolean {
        if (!loaded) return false
        ps1CoreAvailable?.let { return it }
        return synchronized(coreAvailabilityLock) {
            ps1CoreAvailable ?: runCatching { nativeHasPs1Core() }
                .getOrDefault(false)
                .also { ps1CoreAvailable = it }
        }
    }

    fun startPs1(
        gamePath: String,
        gameKey: String,
        systemDir: String,
        saveDir: String,
        stateDir: String,
        surface: Surface,
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean,
        coreOptions: String,
        dualShock: Boolean
    ): Boolean {
        if (!loaded) return false
        val started = runCatching {
            nativeStartPs1(
                gamePath, gameKey, systemDir, saveDir, stateDir, surface,
                performancePolicy, audioBufferBursts, tryExclusiveAudio,
                preferPowerEfficiency, aggressiveFramePacing, coreOptions, dualShock
            )
        }.getOrDefault(false)
        if (started) {
            activeGameKey = gameKey
            activeStateDir = stateDir
            stateLoadGeneration.incrementAndGet()
        }
        return started
    }

    fun stop() {
        stateLoadGeneration.incrementAndGet()
        activeGameKey = ""
        activeStateDir = ""
        if (loaded) runCatching { nativeStop() }
    }

    fun isRunning(): Boolean = loaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun setButton(id: Int, pressed: Boolean) {
        if (loaded) runCatching { nativeSetButton(id, pressed) }
    }

    fun setAnalog(stick: Int, x: Float, y: Float) {
        if (!loaded) return
        val sx = (x.coerceIn(-1f, 1f) * 32767f).toInt()
        val sy = (y.coerceIn(-1f, 1f) * 32767f).toInt()
        runCatching { nativeSetAnalog(stick, sx, sy) }
    }

    /**
     * Publishes player intent to the PS1-only frame-synchronous modern-control layer.
     * False means the active runtime does not expose that layer, so callers may keep
     * their legacy Kotlin fallback. Other console runtimes never receive this signal.
     */
    fun setModernTankIntent(x: Float, y: Float, active: Boolean): Boolean {
        if (!loaded) return false
        val sx = (x.coerceIn(-1f, 1f) * 32767f).toInt()
        val sy = (y.coerceIn(-1f, 1f) * 32767f).toInt()
        return runCatching { nativeSetModernTankIntent(sx, sy, active) }.getOrDefault(false)
    }

    fun saveState(slot: Int = 0) {
        if (loaded) runCatching { nativeSaveState(slot.coerceIn(0, 9)) }
    }

    fun loadState(slot: Int = 0) {
        if (!loaded) return
        val safeSlot = slot.coerceIn(0, 9)
        val stateDir = activeStateDir
        val gameKey = activeGameKey
        if (stateDir.isBlank() || gameKey.isBlank()) {
            runCatching { nativeLoadState(safeSlot) }
            return
        }

        val generation = stateLoadGeneration.incrementAndGet()
        stateLoadExecutor.execute {
            if (generation != stateLoadGeneration.get()) return@execute
            warmStateFile(File(stateDir, "${safeGameKey(gameKey)}.state$safeSlot"))
            if (generation == stateLoadGeneration.get()) runCatching { nativeLoadState(safeSlot) }
        }
    }

    fun resetCheats() {
        if (loaded) runCatching { nativeResetCheats() }
    }

    fun setCheat(index: Int, enabled: Boolean, code: String) {
        if (!loaded || code.isBlank()) return
        runCatching { nativeSetCheat(index.coerceIn(0, 127), enabled, code.take(8192)) }
    }

    fun diskState(): DiskState {
        if (!loaded || !isRunning()) return DiskState(0, 0, false)
        return runCatching {
            val count = nativeDiskCount().coerceAtLeast(0)
            val index = nativeDiskIndex().coerceIn(0, (count - 1).coerceAtLeast(0))
            DiskState(count = count, index = index, ejected = nativeDiskEjected())
        }.getOrDefault(DiskState(0, 0, false))
    }

    fun setDiskIndex(index: Int): Boolean {
        if (!loaded || index < 0) return false
        return runCatching { nativeSetDiskIndex(index) }.getOrDefault(false)
    }

    private fun warmStateFile(file: File) {
        if (!file.isFile || file.length() <= 0L) return
        runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (input.read(buffer) >= 0) Unit
            }
        }
    }

    private fun safeGameKey(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_') }
    }.ifBlank { "game" }

    fun lastMessage(): String =
        if (loaded) runCatching { nativeLastMessage() }.getOrDefault("Runtime indisponível")
        else "Runtime indisponível"

    fun updatePerformancePolicy(
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean
    ) {
        if (loaded) runCatching {
            nativeUpdatePerformancePolicy(
                performancePolicy, audioBufferBursts, tryExclusiveAudio,
                preferPowerEfficiency, aggressiveFramePacing
            )
        }
    }

    private external fun nativeRuntimeVersion(): String
    private external fun nativeHasPs1Core(): Boolean
    private external fun nativeStartPs1(
        gamePath: String,
        gameKey: String,
        systemDir: String,
        saveDir: String,
        stateDir: String,
        surface: Surface,
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean,
        coreOptions: String,
        dualShock: Boolean
    ): Boolean
    private external fun nativeStop()
    private external fun nativeUpdatePerformancePolicy(
        performancePolicy: Int,
        audioBufferBursts: Int,
        tryExclusiveAudio: Boolean,
        preferPowerEfficiency: Boolean,
        aggressiveFramePacing: Boolean
    )
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetButton(id: Int, pressed: Boolean)
    private external fun nativeSetAnalog(stick: Int, x: Int, y: Int)
    private external fun nativeSetModernTankIntent(x: Int, y: Int, active: Boolean): Boolean
    private external fun nativeSaveState(slot: Int)
    private external fun nativeLoadState(slot: Int)
    private external fun nativeResetCheats()
    private external fun nativeSetCheat(index: Int, enabled: Boolean, code: String)
    private external fun nativeDiskCount(): Int
    private external fun nativeDiskIndex(): Int
    private external fun nativeDiskEjected(): Boolean
    private external fun nativeSetDiskIndex(index: Int): Boolean
    private external fun nativeLastMessage(): String
}
