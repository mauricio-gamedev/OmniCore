package com.omnicore.emulator.emulation

import com.omnicore.emulator.core.nativebridge.NativeBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Frontend for PS1 Modern Control Engine v2.1.
 *
 * Preferred path: publish continuous analog intent to the PS1 native host. The host
 * filters and sigma-delta-modulates turn strength once per emulated frame immediately
 * before retro_run(), so steering is independent from Android timer jitter.
 *
 * R5 keeps the precise R4 forward/back behavior, but gives deliberate diagonals more
 * steering authority with a softer mid-stick curve, gated diagonal boost and strong
 * edge response. The generated D-pad direction is still pulsed rather than held, so
 * tank-control games keep smooth course correction instead of an always-on diagonal.
 *
 * Compatibility path: if the active runtime does not expose the native engine, keep
 * a mirrored millisecond PWM fallback. Touch/physical callers therefore do not need
 * a second code path and legacy APK/runtime combinations fail gracefully.
 */
internal class TankMovementAssist {
    private var targetX = 0f
    private var targetY = 0f
    private var gestureActive = false
    private var nativeDelegated = false

    // Compatibility fallback state. Dormant whenever nativeDelegated == true.
    private var movementActive = false
    private var pulseEpochMs = 0L
    private var lastTurnSign = 0
    private var wasSteering = false

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

        if (gestureActive) {
            // Retry delegation while a gesture is active. This covers the brief case
            // where the overlay receives input before the native PS1 session finishes booting.
            if (NativeBridge.setModernTankIntent(targetX, targetY, active = true)) {
                nativeDelegated = true
            }
        }
    }

    fun step(nowMs: Long): Set<Int> {
        // Native generated D-pad bits are OR'ed into the PS1 host input mask. Returning
        // NONE here also clears any fallback bits that may have existed before a late
        // delegation succeeded.
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
            movementActive = false
            lastTurnSign = 0
            wasSteering = false
            return NONE
        }

        val ax = abs(x)
        val ay = abs(y)
        val turnSign = when {
            x <= -TURN_AXIS_DEADZONE -> -1
            x >= TURN_AXIS_DEADZONE -> 1
            else -> 0
        }

        // Keep deliberate horizontal input as direct turn-in-place. The R5 steering
        // changes only the moving-curve path, so alignment behavior stays predictable.
        if (turnSign != 0 && ax >= TURN_IN_PLACE_X && ay <= TURN_IN_PLACE_Y) {
            lastTurnSign = turnSign
            wasSteering = true
            pulseEpochMs = nowMs
            return if (turnSign < 0) LEFT else RIGHT
        }

        val moveSign = when {
            y <= -MOVE_AXIS_DEADZONE -> -1
            y >= MOVE_AXIS_DEADZONE -> 1
            ay >= ax * VERTICAL_DOMINANCE && y < 0f -> -1
            ay >= ax * VERTICAL_DOMINANCE && y > 0f -> 1
            else -> 0
        }

        if (moveSign == 0) {
            wasSteering = turnSign != 0
            if (turnSign == 0) return NONE
            if (turnSign != lastTurnSign) pulseEpochMs = nowMs
            lastTurnSign = turnSign
            return if (turnSign < 0) LEFT else RIGHT
        }

        val moveButton = if (moveSign < 0) UP else DOWN
        if (turnSign == 0 || ax <= STRAIGHT_STEER_DEADZONE || ax < ay * STRAIGHT_CONE_RATIO) {
            lastTurnSign = 0
            wasSteering = false
            return moveButton
        }

        if (!wasSteering || turnSign != lastTurnSign) pulseEpochMs = nowMs
        wasSteering = true
        lastTurnSign = turnSign

        val normalized = ((ax - STRAIGHT_STEER_DEADZONE) / (1f - STRAIGHT_STEER_DEADZONE))
            .coerceIn(0f, 1f)

        // More linear mid-stick response than R4. Small deflection stays precise;
        // a real diagonal receives visibly stronger steering without becoming a held
        // digital diagonal.
        val curved = CURVE_LINEAR * normalized + CURVE_QUADRATIC * normalized * normalized
        val baseDuty = MIN_TURN_DUTY + (MAX_TURN_DUTY - MIN_TURN_DUTY) * curved

        val minAxis = min(ax, ay)
        val maxAxis = max(ax, ay).coerceAtLeast(0.0001f)
        val diagonalRatio = (minAxis / maxAxis).coerceIn(0f, 1f)
        val diagonalGate = ((minAxis - DIAGONAL_GATE_START) / DIAGONAL_GATE_RANGE).coerceIn(0f, 1f)
        val diagonalBoost = DIAGONAL_BOOST * diagonalRatio * diagonalGate
        val edgeBoost = EDGE_BOOST * ((ax - EDGE_BOOST_START) / EDGE_BOOST_RANGE).coerceIn(0f, 1f)

        val duty = (baseDuty + diagonalBoost + edgeBoost).coerceIn(MIN_TURN_DUTY, MAX_TURN_DUTY)
        val activeMs = (TURN_PULSE_PERIOD_MS * duty).toLong().coerceAtLeast(MIN_TURN_PULSE_MS)
        val phase = ((nowMs - pulseEpochMs).coerceAtLeast(0L) % TURN_PULSE_PERIOD_MS)
        if (phase >= activeMs) return moveButton

        return when {
            moveSign < 0 && turnSign < 0 -> UP_LEFT
            moveSign < 0 && turnSign > 0 -> UP_RIGHT
            moveSign > 0 && turnSign < 0 -> DOWN_LEFT
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
        movementActive = false
        pulseEpochMs = 0L
        lastTurnSign = 0
        wasSteering = false
    }

    private companion object {
        const val INPUT_EPSILON = 0.02f
        const val TARGET_ENTER_DEADZONE = 0.17f
        const val TARGET_EXIT_DEADZONE = 0.11f
        const val MOVE_AXIS_DEADZONE = 0.22f
        const val TURN_AXIS_DEADZONE = 0.13f
        const val VERTICAL_DOMINANCE = 0.72f

        const val TURN_IN_PLACE_X = 0.48f
        const val TURN_IN_PLACE_Y = 0.24f
        const val STRAIGHT_STEER_DEADZONE = 0.12f
        const val STRAIGHT_CONE_RATIO = 0.18f

        const val TURN_PULSE_PERIOD_MS = 96L
        const val MIN_TURN_PULSE_MS = 18L
        const val MIN_TURN_DUTY = 0.16f
        const val MAX_TURN_DUTY = 0.98f
        const val CURVE_LINEAR = 0.62f
        const val CURVE_QUADRATIC = 0.38f

        const val DIAGONAL_GATE_START = 0.20f
        const val DIAGONAL_GATE_RANGE = 0.42f
        const val DIAGONAL_BOOST = 0.16f
        const val EDGE_BOOST_START = 0.72f
        const val EDGE_BOOST_RANGE = 0.28f
        const val EDGE_BOOST = 0.06f

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
