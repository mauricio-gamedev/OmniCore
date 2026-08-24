package com.omnicore.emulator.emulation

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Modern steering layer for digital PS1 games with tank controls.
 *
 * A raw digital diagonal is too aggressive for an analog stick: holding UP+RIGHT in
 * a tank-control game means "turn right at full speed while moving forward". That is
 * why the previous deterministic mapping still felt awkward in Resident Evil, Tomb
 * Raider and similar games.
 *
 * This resolver keeps forward/backward held, but converts horizontal stick strength
 * into short D-pad turn pulses. Small sideways deflection produces occasional gentle
 * correction; a large deflection produces longer pulses; near-horizontal input keeps
 * a normal full-speed turn in place. It never estimates character heading and never
 * reads game memory or changes core timing.
 */
internal class TankMovementAssist {
    private var targetX = 0f
    private var targetY = 0f
    private var movementActive = false
    private var pulseEpochMs = 0L
    private var lastTurnSign = 0
    private var wasSteering = false

    fun beginGesture(nowMs: Long) {
        reset()
        pulseEpochMs = nowMs
    }

    fun updateTarget(x: Float, y: Float) {
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (magnitude < INPUT_EPSILON) {
            targetX = 0f
            targetY = 0f
            return
        }
        val scale = if (magnitude > 1f) 1f / magnitude else 1f
        targetX = (x * scale).coerceIn(-1f, 1f)
        targetY = (y * scale).coerceIn(-1f, 1f)
    }

    fun step(nowMs: Long): Set<Int> {
        val x = targetX
        val y = targetY
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()

        // Schmitt deadzone avoids stop/start chatter when the thumb hovers near centre.
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

        // A nearly horizontal stick means an intentional turn in place. Do not pulse
        // here; the player expects immediate, full left/right rotation for alignment.
        if (turnSign != 0 && ax >= TURN_IN_PLACE_X && ay <= TURN_IN_PLACE_Y) {
            lastTurnSign = turnSign
            wasSteering = true
            pulseEpochMs = nowMs
            return if (turnSign < 0) LEFT else RIGHT
        }

        val moveSign = when {
            y <= -MOVE_AXIS_DEADZONE -> -1
            y >= MOVE_AXIS_DEADZONE -> 1
            // When the stick is mostly vertical, preserve movement even if the value
            // sits just below the normal threshold after touch deadzone remapping.
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

        // Straight-forward cone. This is deliberately wider than a raw axis deadzone
        // so natural thumb drift does not make the character snake through corridors.
        if (turnSign == 0 || ax <= STRAIGHT_STEER_DEADZONE || ax < ay * STRAIGHT_CONE_RATIO) {
            lastTurnSign = 0
            wasSteering = false
            return moveButton
        }

        if (!wasSteering || turnSign != lastTurnSign) {
            // Start every new steering correction with an active pulse. This keeps
            // quick course corrections responsive instead of waiting for a PWM phase.
            pulseEpochMs = nowMs
        }
        wasSteering = true
        lastTurnSign = turnSign

        val normalized = ((ax - STRAIGHT_STEER_DEADZONE) / (1f - STRAIGHT_STEER_DEADZONE))
            .coerceIn(0f, 1f)
        // Soft quadratic curve: centre = precise micro-correction, edge = strong turn.
        val curved = 0.32f * normalized + 0.68f * normalized * normalized
        val duty = (MIN_TURN_DUTY + (MAX_TURN_DUTY - MIN_TURN_DUTY) * curved)
            .coerceIn(MIN_TURN_DUTY, MAX_TURN_DUTY)
        val activeMs = (TURN_PULSE_PERIOD_MS * duty).toLong().coerceAtLeast(MIN_TURN_PULSE_MS)
        val phase = ((nowMs - pulseEpochMs).coerceAtLeast(0L) % TURN_PULSE_PERIOD_MS)
        val turningNow = phase < activeMs

        if (!turningNow) return moveButton
        return when {
            moveSign < 0 && turnSign < 0 -> UP_LEFT
            moveSign < 0 && turnSign > 0 -> UP_RIGHT
            moveSign > 0 && turnSign < 0 -> DOWN_LEFT
            else -> DOWN_RIGHT
        }
    }

    fun reset() {
        targetX = 0f
        targetY = 0f
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
        const val TURN_AXIS_DEADZONE = 0.16f
        const val VERTICAL_DOMINANCE = 0.72f

        const val TURN_IN_PLACE_X = 0.48f
        const val TURN_IN_PLACE_Y = 0.24f
        const val STRAIGHT_STEER_DEADZONE = 0.14f
        const val STRAIGHT_CONE_RATIO = 0.20f

        const val TURN_PULSE_PERIOD_MS = 96L
        const val MIN_TURN_PULSE_MS = 18L
        const val MIN_TURN_DUTY = 0.20f
        const val MAX_TURN_DUTY = 0.92f

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
