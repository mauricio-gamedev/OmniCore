package com.omnicore.emulator.emulation

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Deterministic steering layer for digital PS1 games with tank controls.
 *
 * The previous implementation estimated the character heading from elapsed time.
 * Different games turn at different speeds, so that estimate drifted and made the
 * left stick feel scrambled. This version is intentionally stateless: the stick is
 * translated directly to ordinary D-pad combinations every frame.
 *
 * Up/down become forward/backward, horizontal input turns in place, and diagonals
 * combine movement + turning. It does not read game memory or modify core timing.
 */
internal class TankMovementAssist {
    private var targetX = 0f
    private var targetY = 0f

    fun beginGesture(nowMs: Long) {
        // Keep the timestamp in the API so touch and physical-controller callers do
        // not need separate paths. Steering itself is deliberately time-independent.
        @Suppress("UNUSED_VARIABLE")
        val ignored = nowMs
        targetX = 0f
        targetY = 0f
    }

    fun updateTarget(x: Float, y: Float) {
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (magnitude < TARGET_DEADZONE) {
            targetX = 0f
            targetY = 0f
            return
        }
        val scale = if (magnitude > 1f) 1f / magnitude else 1f
        targetX = (x * scale).coerceIn(-1f, 1f)
        targetY = (y * scale).coerceIn(-1f, 1f)
    }

    fun step(nowMs: Long): Set<Int> {
        @Suppress("UNUSED_VARIABLE")
        val ignored = nowMs

        val x = targetX
        val y = targetY
        if (hypot(x.toDouble(), y.toDouble()) < TARGET_DEADZONE) return NONE

        val horizontal = when {
            x <= -TURN_THRESHOLD -> -1
            x >= TURN_THRESHOLD -> 1
            else -> 0
        }
        val vertical = when {
            y <= -MOVE_THRESHOLD -> -1 // screen up -> forward
            y >= MOVE_THRESHOLD -> 1  // screen down -> backward
            else -> 0
        }

        return when {
            vertical < 0 && horizontal < 0 -> UP_LEFT
            vertical < 0 && horizontal > 0 -> UP_RIGHT
            vertical < 0 -> UP
            vertical > 0 && horizontal < 0 -> DOWN_LEFT
            vertical > 0 && horizontal > 0 -> DOWN_RIGHT
            vertical > 0 -> DOWN
            horizontal < 0 -> LEFT
            horizontal > 0 -> RIGHT
            // Near a sector boundary, prefer the dominant axis instead of dropping
            // input completely. This removes the jittery/dead feeling around diagonals.
            abs(y) >= abs(x) && y < 0f -> UP
            abs(y) >= abs(x) && y > 0f -> DOWN
            x < 0f -> LEFT
            x > 0f -> RIGHT
            else -> NONE
        }
    }

    fun reset() {
        targetX = 0f
        targetY = 0f
    }

    private companion object {
        const val TARGET_DEADZONE = 0.18f
        const val MOVE_THRESHOLD = 0.30f
        const val TURN_THRESHOLD = 0.30f

        val NONE: Set<Int> = emptySet()
        val UP: Set<Int> = setOf(4)
        val DOWN: Set<Int> = setOf(5)
        val LEFT: Set<Int> = setOf(6)
        val RIGHT: Set<Int> = setOf(7)
        val UP_LEFT: Set<Int> = setOf(4, 6)
        val UP_RIGHT: Set<Int> = setOf(4, 7)
        val DOWN_LEFT: Set<Int> = setOf(5, 6)
        val DOWN_RIGHT: Set<Int> = setOf(5, 7)
    }
}
