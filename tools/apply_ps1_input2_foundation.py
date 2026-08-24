from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / "app/src/main/java/com/omnicore/emulator/settings/InputSettings.kt"
OVERLAY = ROOT / "app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt"
ASSIST = ROOT / "app/src/main/java/com/omnicore/emulator/emulation/TankMovementAssist.kt"
WORKFLOW = ROOT / ".github/workflows/ps1-input2-apply.yml"
SELF = Path(__file__).resolve()


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


# 1) Add an opt-in mode only. Existing SMART/NATIVE/DPAD semantics and defaults remain unchanged.
input_text = INPUT.read_text()
input_text = replace_exact(
    input_text,
    '''    enum class AnalogMode(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Analógico nativo + D-pad para jogos antigos"),
        NATIVE("native", "Nativo", "Envia somente eixos analógicos DualShock"),
        DPAD("dpad", "D-pad", "Stick touch funciona como direcional digital")
    }
''',
    '''    enum class AnalogMode(val storage: String, val label: String, val subtitle: String) {
        SMART("smart", "Inteligente", "Analógico nativo + D-pad para jogos antigos"),
        NATIVE("native", "Nativo", "Envia somente eixos analógicos DualShock"),
        DPAD("dpad", "D-pad", "Stick touch funciona como direcional digital"),
        TANK_ASSIST("tank_assist", "Movimento moderno", "Stick vira intenção de direção para jogos com controle tank")
    }
''',
    "AnalogMode enum",
)
input_text = replace_exact(
    input_text,
    '    fun saveAnalogMode(context: Context, mode: AnalogMode) { edit(context).putString(KEY_ANALOG_MODE, mode.storage).apply() }\n',
    '''    fun saveAnalogMode(context: Context, mode: AnalogMode) { edit(context).putString(KEY_ANALOG_MODE, mode.storage).apply() }
    fun saveGameAnalogMode(context: Context, gameKey: String, mode: AnalogMode) {
        edit(context).putString(gamePrefix(gameKey) + KEY_ANALOG_MODE, mode.storage).apply()
    }
    fun clearGameAnalogMode(context: Context, gameKey: String) {
        edit(context).remove(gamePrefix(gameKey) + KEY_ANALOG_MODE).apply()
    }
''',
    "per-game analog helpers",
)
INPUT.write_text(input_text)


# 2) Keep the movement algorithm isolated from rendering and libretro/native code.
ASSIST.write_text('''package com.omnicore.emulator.emulation

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
''')


# 3) Wire the opt-in mode into the touch overlay without changing legacy modes.
overlay = OVERLAY.read_text()
overlay = replace_exact(
    overlay,
    '''    private var lastAnalogX = Float.NaN
    private var lastAnalogY = Float.NaN
''',
    '''    private var lastAnalogX = Float.NaN
    private var lastAnalogY = Float.NaN
    private val tankAssist = TankMovementAssist()
    private var tankAssistLoopRunning = false
    private val tankAssistRunnable = object : Runnable {
        override fun run() {
            if (!tankAssistLoopRunning) return
            if (!isAttachedToWindow || analogPointerId == -1 || config.analogMode != InputSettings.AnalogMode.TANK_ASSIST) {
                stopTankAssistLoop(clearButtons = config.analogMode == InputSettings.AnalogMode.TANK_ASSIST)
                return
            }
            applyTankAssistFrame()
            postOnAnimation(this)
        }
    }
''',
    "tank assist fields",
)
overlay = replace_exact(
    overlay,
    '''        removeCallbacks(legacyStatusGuard)
        removeCallbacks(cheatApplyGuard)
        super.onDetachedFromWindow()
''',
    '''        removeCallbacks(legacyStatusGuard)
        removeCallbacks(cheatApplyGuard)
        stopTankAssistLoop(clearButtons = true)
        super.onDetachedFromWindow()
''',
    "detach cleanup",
)
overlay = replace_exact(
    overlay,
    '''                if (analogPointerId == -1 && insideAnalog(x, y, 1.55f)) {
                    analogPointerId = pointerId
                    buttonPointerTargets.delete(pointerId)
                    if (config.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
''',
    '''                if (analogPointerId == -1 && insideAnalog(x, y, 1.55f)) {
                    analogPointerId = pointerId
                    buttonPointerTargets.delete(pointerId)
                    if (config.analogMode == InputSettings.AnalogMode.TANK_ASSIST) {
                        tankAssist.beginGesture(SystemClock.uptimeMillis())
                        ensureTankAssistLoop()
                    }
                    if (config.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
''',
    "analog gesture start",
)
overlay = replace_exact(
    overlay,
    '''                if (pointerId == analogPointerId) {
                    analogPointerId = -1
                    updateAnalog(analogCx, analogCy)
                }
''',
    '''                if (pointerId == analogPointerId) {
                    if (config.analogMode == InputSettings.AnalogMode.TANK_ASSIST) stopTankAssistLoop(clearButtons = true)
                    analogPointerId = -1
                    updateAnalog(analogCx, analogCy)
                }
''',
    "analog gesture end",
)
overlay = replace_exact(
    overlay,
    '''                buttonPointerTargets.clear()
                regionPressed = emptySet()
                analogPointerId = -1
                updateAnalog(analogCx, analogCy)
''',
    '''                buttonPointerTargets.clear()
                regionPressed = emptySet()
                if (config.analogMode == InputSettings.AnalogMode.TANK_ASSIST) stopTankAssistLoop(clearButtons = true)
                analogPointerId = -1
                updateAnalog(analogCx, analogCy)
''',
    "analog cancel",
)
overlay = replace_exact(
    overlay,
    '''    private fun showStatusDialog() {
        val enabled = CheatStore.load(context, gameKey).count { it.enabled }
        AlertDialog.Builder(context)
            .setTitle(gameTitle)
            .setMessage("${NativeBridge.lastMessage()}\\n\\nPreset: ${config.overlayPreset.label}\\nCheats ativos: $enabled")
            .setPositiveButton("OK", null)
            .show()
    }
''',
    '''    private fun showStatusDialog() {
        val enabled = CheatStore.load(context, gameKey).count { it.enabled }
        AlertDialog.Builder(context)
            .setTitle(gameTitle)
            .setMessage("${NativeBridge.lastMessage()}\\n\\nPreset: ${config.overlayPreset.label}\\nControle: ${config.analogMode.label}\\nCheats ativos: $enabled")
            .setNeutralButton("Controle") { _, _ -> showControlDialog() }
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showControlDialog() {
        val modes = InputSettings.AnalogMode.entries
        val selected = modes.indexOf(config.analogMode).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Controle • $gameTitle")
            .setSingleChoiceItems(modes.map { "${it.label} — ${it.subtitle}" }.toTypedArray(), selected) { dialog, which ->
                releaseAll()
                InputSettings.saveGameAnalogMode(context, gameKey, modes[which])
                config = InputSettings.resolveForGame(context, gameKey)
                tankAssist.reset()
                dialog.dismiss()
                showToast("${config.analogMode.label} aplicado só a este jogo")
            }
            .setNeutralButton("Herdar global") { _, _ ->
                releaseAll()
                InputSettings.clearGameAnalogMode(context, gameKey)
                config = InputSettings.resolveForGame(context, gameKey)
                tankAssist.reset()
                showToast("Controle deste jogo voltou ao padrão global")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
''',
    "control dialog",
)
overlay = replace_exact(
    overlay,
    '''    fun releaseAll() {
        buttonPointerTargets.clear()
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
''',
    '''    fun releaseAll() {
        stopTankAssistLoop(clearButtons = false)
        tankAssist.reset()
        buttonPointerTargets.clear()
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
''',
    "release cleanup",
)
overlay = replace_exact(
    overlay,
    '''            InputSettings.AnalogMode.SMART -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                dpadProjection(dx, dy)
            }
        }
''',
    '''            InputSettings.AnalogMode.SMART -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                dpadProjection(dx, dy)
            }
            InputSettings.AnalogMode.TANK_ASSIST -> {
                if (analogChanged) NativeBridge.setAnalog(0, 0f, 0f)
                tankAssist.updateTarget(dx, dy)
                if (analogPointerId != -1) {
                    ensureTankAssistLoop()
                    tankAssist.step(SystemClock.uptimeMillis())
                } else {
                    tankAssist.reset()
                    emptySet()
                }
            }
        }
''',
    "tank mode branch",
)
overlay = replace_exact(
    overlay,
    '''    private fun dpadProjection(x: Float, y: Float): Set<Int> {
''',
    '''    private fun ensureTankAssistLoop() {
        if (tankAssistLoopRunning || analogPointerId == -1 || config.analogMode != InputSettings.AnalogMode.TANK_ASSIST) return
        tankAssistLoopRunning = true
        postOnAnimation(tankAssistRunnable)
    }

    private fun applyTankAssistFrame() {
        val next = tankAssist.step(SystemClock.uptimeMillis())
        if (next != analogDpadPressed) {
            analogDpadPressed = next
            commitButtons()
        }
    }

    private fun stopTankAssistLoop(clearButtons: Boolean) {
        tankAssistLoopRunning = false
        removeCallbacks(tankAssistRunnable)
        tankAssist.reset()
        if (clearButtons && analogDpadPressed.isNotEmpty()) {
            analogDpadPressed = emptySet()
            commitButtons()
        }
    }

    private fun dpadProjection(x: Float, y: Float): Set<Int> {
''',
    "tank loop methods",
)
OVERLAY.write_text(overlay)

# Guardrails: legacy modes/defaults stay present and tank mode is isolated/opt-in.
final_input = INPUT.read_text()
final_overlay = OVERLAY.read_text()
assert 'prefs.getString(KEY_ANALOG_MODE, AnalogMode.SMART.storage)' in final_input
assert 'InputSettings.AnalogMode.SMART ->' in final_overlay
assert 'InputSettings.AnalogMode.NATIVE ->' in final_overlay
assert 'InputSettings.AnalogMode.DPAD ->' in final_overlay
assert 'InputSettings.AnalogMode.TANK_ASSIST ->' in final_overlay
assert 'saveGameAnalogMode' in final_input and 'clearGameAnalogMode' in final_input
assert ASSIST.exists()

# One-shot plumbing: leave the branch with source changes only.
if WORKFLOW.exists():
    WORKFLOW.unlink()
if SELF.exists():
    SELF.unlink()

print("PS1_INPUT2_FOUNDATION_OK legacy_modes_preserved=1 tank_assist_opt_in=1 per_game=1")
