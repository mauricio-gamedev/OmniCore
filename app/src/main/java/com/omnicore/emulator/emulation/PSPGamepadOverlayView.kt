package com.omnicore.emulator.emulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import com.omnicore.emulator.core.psp.PSPNativeBridge
import kotlin.math.hypot
import kotlin.math.min

/** Lightweight PSP-specific multitouch layout; ids match upstream PPSSPP libretro mapping. */
class PSPGamepadOverlayView(context: Context) : View(context) {
    private data class ButtonRegion(
        val id: Int,
        val label: String,
        val nx: Float,
        val ny: Float,
        val factor: Float,
        val accent: Int,
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 1f
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val analogFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 134, 201, 255) }
    private val analogRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 201, 226, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.6f
    }
    private val analogKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 229, 239, 255) }

    private val buttons = arrayOf(
        ButtonRegion(4, "↑", 0.135f, 0.535f, 0.047f, COLOR_BLUE),
        ButtonRegion(5, "↓", 0.135f, 0.685f, 0.047f, COLOR_BLUE),
        ButtonRegion(6, "←", 0.075f, 0.610f, 0.047f, COLOR_BLUE),
        ButtonRegion(7, "→", 0.195f, 0.610f, 0.047f, COLOR_BLUE),
        ButtonRegion(10, "L", 0.105f, 0.105f, 0.041f, COLOR_BLUE),
        ButtonRegion(11, "R", 0.895f, 0.105f, 0.041f, COLOR_BLUE),
        ButtonRegion(9, "△", 0.865f, 0.435f, 0.050f, COLOR_GREEN),
        ButtonRegion(1, "□", 0.805f, 0.555f, 0.050f, COLOR_PURPLE),
        ButtonRegion(8, "○", 0.925f, 0.555f, 0.050f, COLOR_RED),
        ButtonRegion(0, "✕", 0.865f, 0.675f, 0.050f, COLOR_BLUE),
        ButtonRegion(2, "SELECT", 0.435f, 0.895f, 0.032f, COLOR_MUTED),
        ButtonRegion(3, "START", 0.565f, 0.895f, 0.032f, COLOR_MUTED)
    )

    private val pointerButtons = SparseIntArray()
    private val pressedIds = HashSet<Int>()
    private var activeUntilMs = 0L
    private var analogCenterX = 0f
    private var analogCenterY = 0f
    private var analogRadius = 1f
    private var analogX = 0f
    private var analogY = 0f
    private var analogPointerId = INVALID_POINTER

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val short = min(w, h).toFloat().coerceAtLeast(1f)
        analogCenterX = w * 0.205f
        analogCenterY = h * 0.795f
        analogRadius = short * 0.112f
        buttons.forEach { button ->
            button.x = w * button.nx
            button.y = h * button.ny
            button.radius = short * button.factor
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = SystemClock.uptimeMillis() <= activeUntilMs ||
            analogPointerId != INVALID_POINTER || pointerButtons.size() > 0
        val alpha = if (active) 168 else 112
        drawAnalog(canvas, alpha)
        buttons.forEach { drawButton(canvas, it, alpha) }
    }

    private fun drawAnalog(canvas: Canvas, alpha: Int) {
        canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, analogFillPaint)
        canvas.drawCircle(analogCenterX, analogCenterY, analogRadius, analogRingPaint)
        val knobX = analogCenterX + analogX * analogRadius * 0.68f
        val knobY = analogCenterY + analogY * analogRadius * 0.68f
        analogKnobPaint.alpha = alpha.coerceIn(60, 205)
        canvas.drawCircle(knobX, knobY, analogRadius * 0.36f, analogKnobPaint)
        analogKnobPaint.alpha = 255
    }

    private fun drawButton(canvas: Canvas, button: ButtonRegion, alpha: Int) {
        val isPressed = button.id in pressedIds
        val radius = button.radius * if (isPressed) 1.05f else 1f
        fillPaint.color = if (isPressed) {
            Color.argb(218, Color.red(button.accent), Color.green(button.accent), Color.blue(button.accent))
        } else {
            Color.argb(alpha, 22, 29, 47)
        }
        canvas.drawCircle(button.x, button.y, radius, fillPaint)
        strokePaint.color = Color.argb(
            if (isPressed) 240 else alpha,
            Color.red(button.accent), Color.green(button.accent), Color.blue(button.accent)
        )
        canvas.drawCircle(button.x, button.y, radius, strokePaint)
        textPaint.textSize = (if (button.label.length == 1) radius * 0.83f else radius * 0.48f)
            .coerceAtLeast(resources.displayMetrics.scaledDensity * 8f)
        val baseline = button.y - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(button.label, button.x, baseline, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                if (analogPointerId == INVALID_POINTER &&
                    hypot(x - analogCenterX, y - analogCenterY) <= analogRadius * 1.45f
                ) {
                    analogPointerId = pointerId
                    updateAnalog(x, y)
                } else {
                    buttons.firstOrNull { hypot(x - it.x, y - it.y) <= it.radius * 1.55f }
                        ?.let { press(pointerId, it.id) }
                }
                activeUntilMs = SystemClock.uptimeMillis() + 1400L
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (analogPointerId != INVALID_POINTER) {
                    val index = event.findPointerIndex(analogPointerId)
                    if (index >= 0) updateAnalog(event.getX(index), event.getY(index))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == analogPointerId) {
                    analogPointerId = INVALID_POINTER
                    analogX = 0f
                    analogY = 0f
                    PSPNativeBridge.setAnalog(0f, 0f)
                }
                release(pointerId)
                activeUntilMs = SystemClock.uptimeMillis() + 900L
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateAnalog(x: Float, y: Float) {
        var dx = ((x - analogCenterX) / analogRadius).coerceIn(-1f, 1f)
        var dy = ((y - analogCenterY) / analogRadius).coerceIn(-1f, 1f)
        val magnitude = hypot(dx, dy)
        if (magnitude < ANALOG_DEADZONE) {
            dx = 0f
            dy = 0f
        } else if (magnitude > 1f) {
            dx /= magnitude
            dy /= magnitude
        }
        analogX = dx
        analogY = dy
        PSPNativeBridge.setAnalog(dx, dy)
    }

    private fun press(pointerId: Int, buttonId: Int) {
        val alreadyHeld = (0 until pointerButtons.size()).any { pointerButtons.valueAt(it) == buttonId }
        pointerButtons.put(pointerId, buttonId)
        if (!alreadyHeld) {
            pressedIds += buttonId
            PSPNativeBridge.setButton(buttonId, true)
        }
    }

    private fun release(pointerId: Int) {
        val index = pointerButtons.indexOfKey(pointerId)
        if (index < 0) return
        val buttonId = pointerButtons.valueAt(index)
        pointerButtons.removeAt(index)
        val stillHeld = (0 until pointerButtons.size()).any { pointerButtons.valueAt(it) == buttonId }
        if (!stillHeld) {
            pressedIds -= buttonId
            PSPNativeBridge.setButton(buttonId, false)
        }
    }

    fun releaseAll() {
        val ids = (0 until pointerButtons.size()).map { pointerButtons.valueAt(it) }.toSet()
        ids.forEach { PSPNativeBridge.setButton(it, false) }
        pointerButtons.clear()
        pressedIds.clear()
        analogPointerId = INVALID_POINTER
        analogX = 0f
        analogY = 0f
        PSPNativeBridge.setAnalog(0f, 0f)
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val INVALID_POINTER = -1
        const val ANALOG_DEADZONE = 0.10f
        const val COLOR_BLUE = 0xFF84C8FF.toInt()
        const val COLOR_GREEN = 0xFF5FD29F.toInt()
        const val COLOR_PURPLE = 0xFFB495FF.toInt()
        const val COLOR_RED = 0xFFFF7084.toInt()
        const val COLOR_MUTED = 0xFF9BA6BE.toInt()
    }
}
