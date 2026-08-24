package com.omnicore.emulator.emulation

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Modern steering layer for digital PS1 games with tank controls.
 *
 * The core still receives ordinary D-pad buttons, but the stick is interpreted as
 * player intent instead of raw four-way input. The resolver uses hysteresis and
 * directional cones so tiny finger/controller noise does not constantly switch
 * between forward, turn and diagonal commands.
 *
 * This remains game-state independent: it does not read character/camera memory and
 * does not change emulation timing. That keeps the assist generic and deterministic.
 */
internal class TankMovementAssist {
    private var targetX = 0f
    private var targetY = 0f
    private var horizontalIntent = 0
    private var verticalIntent = 0
    private var movementActive = false

    fun beginGesture(nowMs: Long) {
        // Timestamp is retained in the API because touch and physical-controller
        // callers share this class. Steering itself is deliberately time-independent.
        @Suppress("UNUSED_VARIABLE")
        val ignored = nowMs
        reset()
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
        @Suppress("UNUSED_VARIABLE")
        val ignored = nowMs

        val x = targetX
        val y = targetY
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat()

        // Schmitt-style deadzone: entering movement needs a stronger deflection than
        // staying active. This removes the stop/start chatter around the centre.
        if (!movementActive) {
            if (magnitude < TARGET_ENTER_DEADZONE) return NONE
            movementActive = true
        } else if (magnitude < TARGET_EXIT_DEADZONE) {
            movementActive = false
            horizontalIntent = 0
            verticalIntent = 0
            return NONE
        }

        verticalIntent = resolveAxisIntent(
            value = y,
            current = verticalIntent,
            enterThreshold = MOVE_ENTER_THRESHOLD,
            exitThreshold = MOVE_EXIT_THRESHOLD
        )
        horizontalIntent = resolveAxisIntent(
            value = x,
            current = horizontalIntent,
            enterThreshold = TURN_ENTER_THRESHOLD,
            exitThreshold = TURN_EXIT_THRESHOLD
        )

        val ax = abs(x)
        val ay = abs(y)

        // Wide straight cone: when the player is clearly pushing forward/backward,
        // small sideways drift should not make the character snake left/right.
        if (verticalIntent != 0 && ax < ay * STRAIGHT_CONE_RATIO && ax < FORCE_TURN_THRESHOLD) {
            horizontalIntent = 0
        }

        // Wide turn-in-place cone: near-horizontal stick movement should rotate the
        // character cleanly instead of accidentally adding a forward/back command.
        if (horizontalIntent != 0 && ay < ax * TURN_ONLY_CONE_RATIO && ay < FORCE_MOVE_THRESHOLD) {
            verticalIntent = 0
        }

        // If both axes are between their normal thresholds, keep a useful dominant
        // direction instead of dropping input. This makes slow thumb arcs predictable.
        if (verticalIntent == 0 && horizontalIntent == 0) {
            if (ay >= ax) {
                verticalIntent = if (y < 0f) -1 else if (y > 0f) 1 else 0
            } else {
                horizontalIntent = if (x < 0f) -1 else if (x > 0f) 1 else 0
            }
        }

        return buttonsFor(verticalIntent, horizontalIntent)
    }

    fun reset() {
        targetX = 0f
        targetY = 0f
        horizontalIntent = 0
        verticalIntent = 0
        movementActive = false
    }

    private fun resolveAxisIntent(
        value: Float,
        current: Int,
        enterThreshold: Float,
        exitThreshold: Float
    ): Int = when (current) {
        -1 -> when {
            value >= enterThreshold -> 1
            value > -exitThreshold -> 0
            else -> -1
        }
        1 -> when {
            value <= -enterThreshold -> -1
            value < exitThreshold -> 0
            else -> 1
        }
        else -> when {
            value <= -enterThreshold -> -1
            value >= enterThreshold -> 1
            else -> 0
        }
    }

    private fun buttonsFor(vertical: Int, horizontal: Int): Set<Int> = when {
        vertical < 0 && horizontal < 0 -> UP_LEFT
        vertical < 0 && horizontal > 0 -> UP_RIGHT
        vertical < 0 -> UP
        vertical > 0 && horizontal < 0 -> DOWN_LEFT
        vertical > 0 && horizontal > 0 -> DOWN_RIGHT
        vertical > 0 -> DOWN
        horizontal < 0 -> LEFT
        horizontal > 0 -> RIGHT
        else -> NONE
    }

    private companion object {
        const val INPUT_EPSILON = 0.02f
        const val TARGET_ENTER_DEADZONE = 0.18f
        const val TARGET_EXIT_DEADZONE = 0.12f

        const val MOVE_ENTER_THRESHOLD = 0.30f
        const val MOVE_EXIT_THRESHOLD = 0.20f
        const val TURN_ENTER_THRESHOLD = 0.32f
        const val TURN_EXIT_THRESHOLD = 0.21f

        const val STRAIGHT_CONE_RATIO = 0.34f
        const val TURN_ONLY_CONE_RATIO = 0.30f
        const val FORCE_TURN_THRESHOLD = 0.46f
        const val FORCE_MOVE_THRESHOLD = 0.44f

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
