package com.omnicore.emulator.emulation

import com.omnicore.emulator.core.nativebridge.NativeBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Frontend for PS1 Modern Control Engine v3 / Adaptive Arc Steering.
 *
 * Preferred path: publish continuous analog intent to the native PS1 host. The native
 * resolver runs once per retro_run frame. This Kotlin implementation is only a safety
 * fallback for a runtime that does not expose the native path.
 */
internal class TankMovementAssist {
    private var targetX = 0f
    private var targetY = 0f
    private var gestureActive = false
    private var nativeDelegated = false

    private var movementActive = false
    private var steeringLatched = false
    private var pulseEpochMs = 0L
    private var lastEffectiveTurnSign = 0
    private var lastMoveSign = 0

    fun beginGesture(nowMs: Long) {
        reset()
        gestureActive = true
        pulseEpochMs = nowMs
        nativeDelegated = NativeBridge.setModernTankIntent(0f, 0f, active = true)
    }

    fun updateTarget(x: Float, y: Float) {
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (magnitude < INPUT_EPSILON) {
            targetX = 0f
            targetY = 0f
        } else {
            val scale = if (magnitude > 1f) 1f / magnitude else 1f
            targetX = (x * scale).coerceIn(-1f, 1f)
            targetY = (y * scale).coerceIn(-1f, 1f)
        }

        if (gestureActive && NativeBridge.setModernTankIntent(targetX, targetY, active = true)) {
            nativeDelegated = true
        }
    }

    fun step(nowMs: Long): Set<Int> {
        if (nativeDelegated) return NONE
        return fallbackStep(nowMs)
    }

    private fun fallbackStep(nowMs: Long): Set<Int> {
        val x = targetX
        val y = targetY
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()

        if (!movementActive) {
            if (magnitude < TARGET_ENTER_DEADZONE) return NONE
            movementActive = true
            pulseEpochMs = nowMs
        } else if (magnitude < TARGET_EXIT_DEADZONE) {
            clearFallbackState()
            return NONE
        }

        val ax = abs(x)
        val ay = abs(y)
        val rawTurnSign = when {
            x <= -TURN_AXIS_DEADZONE -> -1
            x >= TURN_AXIS_DEADZONE -> 1
            else -> 0
        }

        if (rawTurnSign != 0 && ax >= TURN_IN_PLACE_X && ay <= TURN_IN_PLACE_Y) {
            steeringLatched = false
            lastEffectiveTurnSign = rawTurnSign
            lastMoveSign = 0
            return if (rawTurnSign < 0) LEFT else RIGHT
        }

        val moveSign = when {
            y <= -MOVE_AXIS_DEADZONE -> -1
            y >= MOVE_AXIS_DEADZONE -> 1
            ay >= ax * VERTICAL_DOMINANCE && y < 0f -> -1
            ay >= ax * VERTICAL_DOMINANCE && y > 0f -> 1
            else -> 0
        }

        if (moveSign == 0) {
            steeringLatched = false
            lastMoveSign = 0
            return when {
                rawTurnSign < 0 -> LEFT
                rawTurnSign > 0 -> RIGHT
                else -> NONE
            }
        }

        val moveButton = if (moveSign < 0) UP else DOWN
        val axisSum = ax + ay
        val steerRatio = if (axisSum > 0.0001f) ax / axisSum else 0f

        if (!steeringLatched) {
            steeringLatched = rawTurnSign != 0 && ax >= STEER_ENTER_AXIS && steerRatio >= STEER_ENTER_RATIO
        } else if (rawTurnSign == 0 || ax < STEER_EXIT_AXIS || steerRatio < STEER_EXIT_RATIO) {
            steeringLatched = false
        }

        if (!steeringLatched) {
            lastEffectiveTurnSign = 0
            lastMoveSign = moveSign
            return moveButton
        }

        // Stick quadrant describes travel direction. Reverse must invert tank rotation.
        val effectiveTurnSign = if (moveSign > 0) -rawTurnSign else rawTurnSign
        if (effectiveTurnSign != lastEffectiveTurnSign || moveSign != lastMoveSign) {
            pulseEpochMs = nowMs
            lastEffectiveTurnSign = effectiveTurnSign
            lastMoveSign = moveSign
        }

        var angleT = ((steerRatio - ANGLE_START) / (ANGLE_FULL - ANGLE_START)).coerceIn(0f, 1f)
        angleT = angleT * angleT * (3f - 2f * angleT)
        var duty = 0.16f + 0.80f * angleT

        val radial = ((magnitude - TARGET_ENTER_DEADZONE) / (1f - TARGET_ENTER_DEADZONE)).coerceIn(0f, 1f)
        duty *= 0.76f + 0.24f * radial

        val diagonalness = if (axisSum > 0.0001f) 1f - abs(ax - ay) / axisSum else 0f
        var diagonalGate = ((min(ax, ay) - DIAGONAL_GATE_START) / DIAGONAL_GATE_RANGE).coerceIn(0f, 1f)
        diagonalGate = diagonalGate * diagonalGate * (3f - 2f * diagonalGate)
        if (diagonalness >= DIAGONALNESS_FLOOR && diagonalGate > 0f) {
            duty = max(duty, (DIAGONAL_DUTY_FLOOR + 0.10f * radial) * diagonalGate)
        }
        duty = duty.coerceIn(0.12f, 0.96f)

        // Compatibility fallback uses short coherent PWM arcs instead of scattered
        // one-frame samples. Native R6 remains the preferred frame-synchronous path.
        val activeMs = max(MIN_ARC_MS.toFloat(), ARC_PERIOD_MS * duty).toLong()
        val phase = (nowMs - pulseEpochMs).coerceAtLeast(0L) % ARC_PERIOD_MS
        if (phase >= activeMs) return moveButton

        return when {
            moveSign < 0 && effectiveTurnSign < 0 -> UP_LEFT
            moveSign < 0 && effectiveTurnSign > 0 -> UP_RIGHT
            moveSign > 0 && effectiveTurnSign < 0 -> DOWN_LEFT
            else -> DOWN_RIGHT
        }
    }

    fun reset() {
        if (gestureActive || nativeDelegated) {
            NativeBridge.setModernTankIntent(0f, 0f, active = false)
        }
        targetX = 0f
        targetY = 0f
        gestureActive = false
        nativeDelegated = false
        clearFallbackState()
    }

    private fun clearFallbackState() {
        movementActive = false
        steeringLatched = false
        pulseEpochMs = 0L
        lastEffectiveTurnSign = 0
        lastMoveSign = 0
    }

    private companion object {
        const val INPUT_EPSILON = 0.02f
        const val TARGET_ENTER_DEADZONE = 0.17f
        const val TARGET_EXIT_DEADZONE = 0.105f
        const val MOVE_AXIS_DEADZONE = 0.21f
        const val TURN_AXIS_DEADZONE = 0.12f
        const val VERTICAL_DOMINANCE = 0.72f

        const val TURN_IN_PLACE_X = 0.44f
        const val TURN_IN_PLACE_Y = 0.23f
        const val STEER_ENTER_RATIO = 0.135f
        const val STEER_EXIT_RATIO = 0.090f
        const val STEER_ENTER_AXIS = 0.115f
        const val STEER_EXIT_AXIS = 0.080f
        const val ANGLE_START = 0.10f
        const val ANGLE_FULL = 0.72f

        const val DIAGONAL_GATE_START = 0.22f
        const val DIAGONAL_GATE_RANGE = 0.30f
        const val DIAGONALNESS_FLOOR = 0.62f
        const val DIAGONAL_DUTY_FLOOR = 0.66f

        const val ARC_PERIOD_MS = 64L
        const val MIN_ARC_MS = 18L

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
