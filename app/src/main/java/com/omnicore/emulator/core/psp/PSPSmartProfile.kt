package com.omnicore.emulator.core.psp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Chooses a conservative PPSSPP quality tier before startup.
 *
 * It adapts internal rendering resolution only; it never enables frame skipping,
 * modifies Android clocks, or writes hidden device properties.
 */
object PSPSmartProfile {
    data class Profile(
        val internalResolution: Int,
        val label: String,
        val reason: String
    ) {
        val summary: String
            get() = "$label • resolução ${internalResolution}× • JIT • OpenGL ES • frame skip desativado • $reason"
    }

    fun select(context: Context): Profile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val totalBytes = memory.totalMem.coerceAtLeast(0L)
        val availableBytes = memory.availMem.coerceAtLeast(0L)
        val lowRamDevice = activityManager?.isLowRamDevice == true
        val powerManager = context.getSystemService(PowerManager::class.java)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val gib = 1024L * 1024L * 1024L
        val canUseTwoX = !lowRamDevice &&
            totalBytes >= 8L * gib &&
            availableBytes >= 3L * gib &&
            thermalStatus < PowerManager.THERMAL_STATUS_SEVERE
        val resolution = if (canUseTwoX) 2 else 1

        val reason = when {
            lowRamDevice -> "Android sinaliza dispositivo com RAM limitada"
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> "perfil reduzido por condição térmica"
            totalBytes < 8L * gib -> "perfil conservador para a memória total do aparelho"
            availableBytes < 3L * gib -> "perfil conservador por memória livre limitada"
            else -> "memória ampla e condição térmica abaixo de severa"
        }
        val label = if (resolution == 2) "Qualidade equilibrada" else "Desempenho conservador"
        return Profile(resolution, label, reason)
    }
}
