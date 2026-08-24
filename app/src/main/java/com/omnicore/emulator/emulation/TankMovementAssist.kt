package com.omnicore.emulator.emulation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Generic, opt-in steering layer for digital PS1 games with tank controls.
 *
 * The emulator still receives only ordinary D-pad presses. No game memory is read,
 * no core timing is changed, and every new touch gesture re-anchors "up" to the
 * character's current forward direction to avoid long-term drift across camera cuts.
 */
internal class TankMovementAssist {
    private var targetMagnitude = 0f
    private var targetAngle = 0f
    private var estimatedHeading = 0f
    private var lastFrameMs = 0L
    private var turnDirection = 0

    fun beginGesture(nowMs: Long) {
        targetMagnitude = 0f
        targetAngle = 0f
        estimatedHeading = 0f
        lastFrameMs = nowMs
        turnDirection = 0
    }

    fun updateTarget(x: Float, y: Float) {
        val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat().coerceIn(0f, 1f)
        if (magnitude < TARGET_DEADZONE) {
            targetMagnitude = 0f
            turnDirection = 0
            return
        }
        targetMagnitude = magnitude
        // Screen up is zero; right is positive. This target is relative to the
        // facing direction captured at the beginning of the current touch gesture.
        targetAngle = atan2(x.toDouble(), (-y).toDouble()).toFloat()
    }

    fun step(nowMs: Long): Set<Int> {
        if (targetMagnitude < TARGET_DEADZONE) {
            lastFrameMs = nowMs
            turnDirection = 0
            return NONE
        }

        val dt = if (lastFrameMs == 0L) 0f else ((nowMs - lastFrameMs) / 1000f).coerceIn(0f, MAX_FRAME_SECONDS)
        lastFrameMs = nowMs

        var delta = wrap(targetAngle - estimatedHeading)
        var error = abs(delta)
        val desiredDirection = when {
            delta > 0f -> 1
            delta < 0f -> -1
            else -> 0
        }

        if (turnDirection == 0) {
            if (error > TURN_START_RAD) turnDirection = desiredDirection
        } else if (error < TURN_STOP_RAD) {
            turnDirection = 0
        } else if (desiredDirection != 0 && desiredDirection != turnDirection && error > TURN_START_RAD) {
            turnDirection = desiredDirection
        }

        if (turnDirection != 0 && dt > 0f) {
            val advance = min(error, TURN_RATE_RAD_PER_SEC * dt)
            estimatedHeading = wrap(estimatedHeading + turnDirection * advance)
            delta = wrap(targetAngle - estimatedHeading)
            error = abs(delta)
            if (error < TURN_STOP_RAD) turnDirection = 0
        }

        val forward = turnDirection == 0 || error <= FORWARD_WHILE_TURNING_RAD
        return when {
            turnDirection < 0 && forward -> UP_LEFT
            turnDirection > 0 && forward -> UP_RIGHT
            turnDirection < 0 -> LEFT
            turnDirection > 0 -> RIGHT
            else -> UP
        }
    }

    fun reset() {
        targetMagnitude = 0f
        targetAngle = 0f
        estimatedHeading = 0f
        lastFrameMs = 0L
        turnDirection = 0
    }

    private fun wrap(value: Float): Float {
        var angle = value
        val pi = PI.toFloat()
        val twoPi = (PI * 2.0).toFloat()
        while (angle > pi) angle -= twoPi
        while (angle < -pi) angle += twoPi
        return angle
    }

    private companion object {
        const val TARGET_DEADZONE = 0.18f
        const val MAX_FRAME_SECONDS = 0.050f
        const val TURN_RATE_RAD_PER_SEC = 3.15f
        const val TURN_START_RAD = 0.31f
        const val TURN_STOP_RAD = 0.15f
        const val FORWARD_WHILE_TURNING_RAD = 0.96f

        val NONE: Set<Int> = emptySet()
        val UP: Set<Int> = setOf(4)
        val LEFT: Set<Int> = setOf(6)
        val RIGHT: Set<Int> = setOf(7)
        val UP_LEFT: Set<Int> = setOf(4, 6)
        val UP_RIGHT: Set<Int> = setOf(4, 7)
    }
}
