package com.omnicore.emulator.emulation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.SparseIntArray
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.cheats.CheatStore
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.settings.InputSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

class GamepadOverlayView(context: Context) : View(context) {
    private data class Region(
        val key: String,
        val id: Int,
        val label: String,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val wide: Boolean = false
    )

    private val activity = context as? Activity
    private val gameKey = activity?.intent?.getStringExtra("gameId").orEmpty().ifBlank { "game" }
    private val gameTitle = activity?.intent?.getStringExtra("gameTitle").orEmpty().ifBlank { "PlayStation" }
    private var config = InputSettings.resolveForGame(context, gameKey)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 238, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.45f * resources.displayMetrics.density
    }
    private val analogBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 185, 255)
        style = Paint.Style.FILL
    }
    private val analogRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * resources.displayMetrics.density
    }
    private val analogKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 222, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 22, 24, 42)
        style = Paint.Style.FILL
    }
    private val chromeAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 105, 87, 235)
        style = Paint.Style.FILL
    }

    private var regions: List<Region> = emptyList()
    private var regionPressed: Set<Int> = emptySet()
    private var analogDpadPressed: Set<Int> = emptySet()
    private var committedButtons: Set<Int> = emptySet()
    private var analogCx = 0f
    private var analogCy = 0f
    private var analogRadius = 0f
    private var analogKnobX = 0f
    private var analogKnobY = 0f
    private var analogPointerId = -1
    private var lastAnalogX = Float.NaN
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

    private var editMode = false
    private var editPointerId = -1
    private var editTargetKey: String? = null
    private var menuVisible = false
    private var activeUntilMs = 0L
    private var fadeScheduledAtMs = 0L
    private var fourFingerGestureLatched = false
    private val buttonPointerTargets = SparseIntArray()
    private var selectedEditKey: String? = null
    private var legacyStatusView: TextView? = null
    private var cheatsAppliedForSession = false
    private val scratchRect = RectF()
    private val dpadProjectionCache: Array<Set<Int>> = Array(16) { mask ->
        buildSet {
            if (mask and 1 != 0) add(4)
            if (mask and 2 != 0) add(5)
            if (mask and 4 != 0) add(6)
            if (mask and 8 != 0) add(7)
        }
    }

    private val menuHideRunnable = Runnable {
        if (!editMode) {
            menuVisible = false
            scheduleRedraw()
        }
    }
    private val fadeRunnable = Runnable {
        fadeScheduledAtMs = 0L
        scheduleRedraw()
    }
    private val legacyStatusGuard = object : Runnable {
        override fun run() {
            val status = legacyStatusView
            if (status != null) {
                val text = status.text?.toString().orEmpty()
                val important = text.startsWith("PREP") || text.startsWith("BOOT E") ||
                    text.startsWith("RUNTIME E") || text.startsWith("RUNTIME W")
                val telemetry = text.startsWith("RUN OK") || text.startsWith("BOOT 6/6")
                status.alpha = when {
                    important -> 1f
                    telemetry && config.showPerformanceHud -> 1f
                    telemetry -> 0f
                    else -> status.alpha
                }
            }
            postDelayed(this, 500)
        }
    }
    private val cheatApplyGuard = object : Runnable {
        override fun run() {
            if (cheatsAppliedForSession || !isAttachedToWindow) return
            if (NativeBridge.isRunning()) {
                applyStoredCheats()
                cheatsAppliedForSession = true
            } else {
                postDelayed(this, 250)
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { suppressLegacyChrome() }
        post(legacyStatusGuard)
        post(cheatApplyGuard)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(menuHideRunnable)
        removeCallbacks(fadeRunnable)
        removeCallbacks(legacyStatusGuard)
        removeCallbacks(cheatApplyGuard)
        stopTankAssistLoop(clearButtons = true)
        super.onDetachedFromWindow()
    }

    private fun suppressLegacyChrome() {
        val root = parent as? ViewGroup ?: return
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child === this) continue
            when (child) {
                is Button -> child.visibility = GONE
                is TextView -> {
                    val params = child.layoutParams as? FrameLayout.LayoutParams
                    val gravity = params?.gravity ?: 0
                    if ((gravity and Gravity.BOTTOM) == Gravity.BOTTOM) child.visibility = GONE
                    else if ((gravity and Gravity.TOP) == Gravity.TOP) legacyStatusView = child
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout(w, h)
    }

    private fun rebuildLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        config = InputSettings.resolveForGame(context, gameKey)
        val base = min(w, h).toFloat()
        val presetScale = when (config.overlayPreset) {
            InputSettings.OverlayPreset.CLEAN -> 0.92f
            InputSettings.OverlayPreset.COMPACT -> 0.82f
            InputSettings.OverlayPreset.STANDARD -> 1f
            InputSettings.OverlayPreset.LEFT, InputSettings.OverlayPreset.RIGHT -> 0.90f
            InputSettings.OverlayPreset.TABLET -> 1.05f
        }
        val scale = config.touchScale * presetScale
        val r = base * 0.060f * scale
        val small = base * 0.046f * scale
        val dpadR = base * 0.047f * scale
        val defaults = presetPositions(config.overlayPreset)

        fun xy(key: String, fallbackX: Float, fallbackY: Float): Pair<Float, Float> =
            defaults[key] ?: (fallbackX to fallbackY)

        val analogDefault = xy("analog", 0.145f, 0.73f)
        val analog = InputSettings.resolveControlPosition(context, "analog", analogDefault.first, analogDefault.second, gameKey)
        analogCx = w * analog.x
        analogCy = h * analog.y
        val analogScale = InputSettings.resolveControlScale(context, "analog", gameKey)
        analogRadius = base * 0.105f * scale * analogScale
        if (analogPointerId == -1) {
            analogKnobX = analogCx
            analogKnobY = analogCy
        }

        fun make(key: String, id: Int, label: String, fx: Float, fy: Float, radius: Float, wide: Boolean = false): Region {
            val d = xy(key, fx, fy)
            val saved = InputSettings.resolveControlPosition(context, key, d.first, d.second, gameKey)
            val individualScale = InputSettings.resolveControlScale(context, key, gameKey)
            return Region(key, id, label, w * saved.x, h * saved.y, radius * individualScale, wide)
        }

        regions = listOf(
            make("dpad_up", 4, "▲", 0.315f, 0.638f, dpadR),
            make("dpad_down", 5, "▼", 0.315f, 0.802f, dpadR),
            make("dpad_left", 6, "◀", 0.233f, 0.72f, dpadR),
            make("dpad_right", 7, "▶", 0.397f, 0.72f, dpadR),
            make("triangle", 9, "△", 0.845f, 0.55f, r),
            make("cross", 0, "×", 0.845f, 0.83f, r),
            make("square", 1, "□", 0.775f, 0.69f, r),
            make("circle", 8, "○", 0.915f, 0.69f, r),
            make("select", 2, "SELECT", 0.465f, 0.84f, small, wide = true),
            make("start", 3, "START", 0.565f, 0.84f, small, wide = true),
            make("l1", 10, "L1", 0.095f, 0.13f, small, wide = true),
            make("l2", 12, "L2", 0.215f, 0.13f, small, wide = true),
            make("r2", 13, "R2", 0.785f, 0.13f, small, wide = true),
            make("r1", 11, "R1", 0.905f, 0.13f, small, wide = true)
        )
    }

    private fun presetPositions(preset: InputSettings.OverlayPreset): Map<String, Pair<Float, Float>> = when (preset) {
        InputSettings.OverlayPreset.STANDARD -> emptyMap()
        InputSettings.OverlayPreset.CLEAN -> mapOf(
            "analog" to (0.115f to 0.77f),
            "dpad_up" to (0.275f to 0.65f), "dpad_down" to (0.275f to 0.81f),
            "dpad_left" to (0.20f to 0.73f), "dpad_right" to (0.35f to 0.73f),
            "triangle" to (0.875f to 0.57f), "cross" to (0.875f to 0.82f),
            "square" to (0.81f to 0.695f), "circle" to (0.94f to 0.695f),
            "select" to (0.47f to 0.91f), "start" to (0.55f to 0.91f),
            "l1" to (0.07f to 0.10f), "l2" to (0.18f to 0.10f),
            "r2" to (0.82f to 0.10f), "r1" to (0.93f to 0.10f)
        )
        InputSettings.OverlayPreset.COMPACT -> mapOf(
            "analog" to (0.10f to 0.80f),
            "triangle" to (0.90f to 0.62f), "cross" to (0.90f to 0.84f),
            "square" to (0.84f to 0.73f), "circle" to (0.96f to 0.73f),
            "select" to (0.48f to 0.92f), "start" to (0.56f to 0.92f),
            "l1" to (0.06f to 0.09f), "l2" to (0.16f to 0.09f),
            "r2" to (0.84f to 0.09f), "r1" to (0.94f to 0.09f)
        )
        InputSettings.OverlayPreset.LEFT -> mapOf(
            "analog" to (0.10f to 0.72f),
            "triangle" to (0.67f to 0.58f), "cross" to (0.67f to 0.82f),
            "square" to (0.61f to 0.70f), "circle" to (0.73f to 0.70f),
            "select" to (0.43f to 0.91f), "start" to (0.51f to 0.91f)
        )
        InputSettings.OverlayPreset.RIGHT -> mapOf(
            "analog" to (0.31f to 0.76f),
            "triangle" to (0.90f to 0.58f), "cross" to (0.90f to 0.82f),
            "square" to (0.84f to 0.70f), "circle" to (0.96f to 0.70f),
            "select" to (0.52f to 0.91f), "start" to (0.60f to 0.91f)
        )
        InputSettings.OverlayPreset.TABLET -> mapOf(
            "analog" to (0.08f to 0.79f),
            "triangle" to (0.92f to 0.58f), "cross" to (0.92f to 0.84f),
            "square" to (0.86f to 0.71f), "circle" to (0.98f to 0.71f),
            "select" to (0.47f to 0.92f), "start" to (0.54f to 0.92f),
            "l1" to (0.05f to 0.09f), "l2" to (0.16f to 0.09f),
            "r2" to (0.84f to 0.09f), "r1" to (0.95f to 0.09f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (config.controlsVisible || editMode) drawControls(canvas)
        if (editMode) drawEditorUi(canvas)
        drawMenuButton(canvas)
        if (menuVisible && !editMode) drawQuickMenu(canvas)
    }

    private fun drawControls(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val idleFactor = if (config.cleanOverlay && config.dynamicOpacity && now > activeUntilMs && committedButtons.isEmpty()) 0.46f else 1f
        val opacity = (config.touchOpacity * idleFactor).coerceIn(0.12f, 1f)
        analogBasePaint.alpha = scaledAlpha(58, opacity)
        analogRingPaint.alpha = scaledAlpha(130, opacity)
        analogKnobPaint.alpha = scaledAlpha(if (analogPointerId == -1) 120 else 190, opacity)
        strokePaint.alpha = scaledAlpha(135, opacity)
        textPaint.alpha = scaledAlpha(240, opacity)

        canvas.drawCircle(analogCx, analogCy, analogRadius, analogBasePaint)
        canvas.drawCircle(analogCx, analogCy, analogRadius, analogRingPaint)
        canvas.drawCircle(analogKnobX, analogKnobY, analogRadius * 0.42f, analogKnobPaint)
        if (config.showLabels || editMode || analogPointerId != -1) {
            textPaint.textSize = min(width, height) * 0.022f * config.touchScale
            canvas.drawText("L", analogCx, analogCy - analogRadius - textPaint.textSize * 0.25f, textPaint)
        }

        textPaint.textSize = min(width, height) * 0.029f * config.touchScale
        regions.forEach { region ->
            if (!isRegionVisible(region)) return@forEach
            val active = region.id in committedButtons
            val alpha = scaledAlpha(if (active) 165 else 54, if (active) config.touchOpacity else opacity)
            fillPaint.color = Color.argb(alpha, if (active) 218 else 235, if (active) 214 else 238, 255)
            if (region.wide) {
                val halfW = region.radius * 1.55f
                val halfH = region.radius * 0.68f
                scratchRect.set(region.cx - halfW, region.cy - halfH, region.cx + halfW, region.cy + halfH)
                canvas.drawRoundRect(scratchRect, halfH, halfH, fillPaint)
                canvas.drawRoundRect(scratchRect, halfH, halfH, strokePaint)
            } else {
                canvas.drawCircle(region.cx, region.cy, region.radius, fillPaint)
                canvas.drawCircle(region.cx, region.cy, region.radius, strokePaint)
            }
            if (config.showLabels || editMode || active) {
                val baseline = region.cy - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(region.label, region.cx, baseline, textPaint)
            }
        }
        textPaint.alpha = 255
    }

    private fun drawMenuButton(canvas: Canvas) {
        val rect = menuButtonRect()
        val paint = if (menuVisible) chromeAccentPaint else chromePaint
        paint.alpha = if (menuVisible) 238 else 145
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        textPaint.alpha = 220
        textPaint.textSize = min(width, height) * 0.032f
        canvas.drawText("⋮", rect.centerX(), textBaseline(rect.centerY()), textPaint)
        textPaint.alpha = 255
    }

    private fun drawQuickMenu(canvas: Canvas) {
        val panel = quickMenuPanelRect()
        chromePaint.alpha = 238
        canvas.drawRoundRect(panel, min(width, height) * 0.025f, min(width, height) * 0.025f, chromePaint)
        val labels = arrayOf("SALVAR", "CARREGAR", "STATUS", "EDITAR", "VISUAL", "LAYOUT", "CHEATS", "DISCO", "SAIR")
        textPaint.textSize = min(width, height) * 0.018f
        labels.forEachIndexed { index, label ->
            val rect = quickMenuItemRect(index)
            fillPaint.color = Color.argb(if (index == 6) 130 else 80, 120, 105, 225)
            canvas.drawRoundRect(rect, rect.height() * 0.24f, rect.height() * 0.24f, fillPaint)
            canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY()), textPaint)
        }
        textPaint.textSize = min(width, height) * 0.014f
        canvas.drawText("4 dedos: ocultar/mostrar overlay", panel.centerX(), panel.bottom - min(width, height) * 0.018f, textPaint)
    }

    private fun drawEditorUi(canvas: Canvas) {
        textPaint.textSize = min(width, height) * 0.016f
        for (index in 0..4) {
            val rect = editorButtonRect(index)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, if (index == 0) chromeAccentPaint else chromePaint)
            val label = when (index) {
                0 -> "CONCLUIR"
                1 -> if (config.showDpad) "SETAS ON" else "SETAS OFF"
                2 -> "RESTAURAR"
                3 -> "TAM −"
                else -> "TAM +"
            }
            canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY()), textPaint)
        }
        textPaint.textSize = min(width, height) * 0.016f
        val selected = selectedEditKey
        val info = if (selected == null) {
            "Toque ou arraste um controle para selecioná-lo"
        } else {
            val scale = (InputSettings.resolveControlScale(context, selected, gameKey) * 100f).toInt()
            "Selecionado: ${selected.replace('_', ' ').uppercase()} • tamanho $scale%"
        }
        canvas.drawText(info, width * 0.5f, height * 0.225f, textPaint)
    }

    private fun menuButtonRect(): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val w = base * 0.075f
        val h = base * 0.060f
        return RectF(width - w - base * 0.018f, base * 0.018f, width - base * 0.018f, base * 0.018f + h)
    }

    private fun quickMenuPanelRect(): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val panelW = base * 0.50f
        val panelH = base * 0.47f
        val right = width - base * 0.018f
        val top = base * 0.09f
        return RectF(right - panelW, top, right, top + panelH)
    }

    private fun quickMenuItemRect(index: Int): RectF {
        val panel = quickMenuPanelRect()
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val gap = base * 0.012f
        val footer = base * 0.045f
        val contentH = panel.height() - footer - gap * 6
        val itemW = (panel.width() - gap * 3) / 2f
        val itemH = contentH / 5f
        val column = index % 2
        val row = index / 2
        val left = panel.left + gap + column * (itemW + gap)
        val top = panel.top + gap + row * (itemH + gap)
        return RectF(left, top, left + itemW, top + itemH)
    }

    private fun editorButtonRect(index: Int): RectF {
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val buttonW = when (index) {
            0 -> base * 0.25f
            3, 4 -> base * 0.18f
            else -> base * 0.21f
        }
        val buttonH = base * 0.062f
        val centerX = when (index) {
            1 -> width * 0.20f
            2 -> width * 0.80f
            3 -> width * 0.39f
            4 -> width * 0.61f
            else -> width * 0.50f
        }
        val centerY = if (index >= 3) height * 0.165f else height * 0.085f
        return RectF(centerX - buttonW / 2f, centerY - buttonH / 2f, centerX + buttonW / 2f, centerY + buttonH / 2f)
    }

    private fun textBaseline(cy: Float): Float = cy - (textPaint.ascent() + textPaint.descent()) / 2f
    private fun scaledAlpha(base: Int, opacity: Float): Int = (base * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        boostInteraction()
        if (handleMultiFingerGesture(event)) return true
        if (handleQuickMenuTouch(event)) return true
        if (editMode) return handleEditorTouch(event)
        if (!config.controlsVisible) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                if (analogPointerId == -1 && insideAnalog(x, y, 1.55f)) {
                    analogPointerId = pointerId
                    buttonPointerTargets.delete(pointerId)
                    if (config.analogMode == InputSettings.AnalogMode.TANK_ASSIST) {
                        tankAssist.beginGesture(SystemClock.uptimeMillis())
                        ensureTankAssistLoop()
                    }
                    if (config.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else {
                    findButtonAt(x, y, 1.34f)?.let { region ->
                        buttonPointerTargets.put(pointerId, region.id)
                        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == analogPointerId) {
                    if (config.analogMode == InputSettings.AnalogMode.TANK_ASSIST) stopTankAssistLoop(clearButtons = true)
                    analogPointerId = -1
                    updateAnalog(analogCx, analogCy)
                }
                buttonPointerTargets.delete(pointerId)
            }
            MotionEvent.ACTION_CANCEL -> {
                buttonPointerTargets.clear()
                regionPressed = emptySet()
                if (config.analogMode == InputSettings.AnalogMode.TANK_ASSIST) stopTankAssistLoop(clearButtons = true)
                analogPointerId = -1
                updateAnalog(analogCx, analogCy)
            }
        }

        if (analogPointerId != -1) {
            val index = event.findPointerIndex(analogPointerId)
            if (index >= 0 && !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == analogPointerId)) {
                updateAnalog(event.getX(index), event.getY(index))
            }
        }

        syncPressedButtonsFromPointers()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    private fun syncPressedButtonsFromPointers() {
        if (buttonPointerTargets.size() == 0) {
            if (regionPressed.isNotEmpty()) {
                regionPressed = emptySet()
                commitButtons()
            }
            return
        }
        val next = HashSet<Int>(buttonPointerTargets.size())
        for (index in 0 until buttonPointerTargets.size()) next.add(buttonPointerTargets.valueAt(index))
        if (next != regionPressed) {
            regionPressed = next
            commitButtons()
        }
    }

    private fun handleMultiFingerGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> fourFingerGestureLatched = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!fourFingerGestureLatched && event.pointerCount >= 4 && event.eventTime - event.downTime <= 500L) {
                    fourFingerGestureLatched = true
                    releaseAll()
                    config = config.copy(controlsVisible = !config.controlsVisible)
                    persistGameConfig()
                    showToast(if (config.controlsVisible) "Overlay visível" else "Modo ultra imersivo")
                    scheduleRedraw()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fourFingerGestureLatched = false
        }
        return fourFingerGestureLatched && event.pointerCount >= 4
    }

    private fun handleQuickMenuTouch(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return menuVisible
        val x = event.getX(event.actionIndex)
        val y = event.getY(event.actionIndex)
        if (menuButtonRect().contains(x, y)) {
            toggleMenu()
            return true
        }
        if (!menuVisible) return false
        val item = (0..8).firstOrNull { quickMenuItemRect(it).contains(x, y) }
        if (item == null) {
            menuVisible = false
            scheduleRedraw()
            return true
        }
        performMenuAction(item)
        return true
    }

    private fun performMenuAction(index: Int) {
        menuVisible = false
        removeCallbacks(menuHideRunnable)
        when (index) {
            0 -> { NativeBridge.saveState(0); showToast("Save state solicitado") }
            1 -> { NativeBridge.loadState(0); showToast("Carregando save state") }
            2 -> showStatusDialog()
            3 -> setEditMode(true)
            4 -> showVisualDialog()
            5 -> showLayoutDialog()
            6 -> showCheatDialog()
            7 -> showDiskDialog()
            8 -> activity?.finish()
        }
        scheduleRedraw()
    }

    private fun toggleMenu() {
        if (editMode) return
        menuVisible = !menuVisible
        removeCallbacks(menuHideRunnable)
        if (menuVisible) postDelayed(menuHideRunnable, 4500)
        scheduleRedraw()
    }

    private fun boostInteraction() {
        val now = SystemClock.uptimeMillis()
        activeUntilMs = now + 650L
        val desiredFade = now + 680L
        if (fadeScheduledAtMs == 0L || desiredFade - fadeScheduledAtMs > 420L) {
            removeCallbacks(fadeRunnable)
            fadeScheduledAtMs = desiredFade
            postDelayed(fadeRunnable, 680L)
        }
    }

    private fun handleEditorTouch(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val actionX = event.getX(actionIndex)
        val actionY = event.getY(actionIndex)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (editorButtonRect(0).contains(actionX, actionY)) { setEditMode(false); return true }
            if (editorButtonRect(1).contains(actionX, actionY)) {
                config = config.copy(showDpad = !config.showDpad)
                persistGameConfig()
                regionPressed = regionPressed.filterNot { it in 4..7 }.toSet()
                commitButtons()
                scheduleRedraw()
                return true
            }
            if (editorButtonRect(2).contains(actionX, actionY)) {
                InputSettings.resetControlPositions(context, gameKey)
                InputSettings.resetControlScales(context, gameKey)
                selectedEditKey = null
                rebuildLayout(width, height)
                showToast("Layout e tamanhos deste jogo restaurados")
                return true
            }
            if (editorButtonRect(3).contains(actionX, actionY)) {
                adjustSelectedControlScale(-0.10f)
                return true
            }
            if (editorButtonRect(4).contains(actionX, actionY)) {
                adjustSelectedControlScale(0.10f)
                return true
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (editPointerId == -1) {
                    editTargetKey = findEditTarget(actionX, actionY)
                    selectedEditKey = editTargetKey
                    if (editTargetKey != null) {
                        editPointerId = event.getPointerId(actionIndex)
                        if (config.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        moveEditTarget(actionX, actionY)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (editPointerId != -1) {
                    val index = event.findPointerIndex(editPointerId)
                    if (index >= 0) moveEditTarget(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == editPointerId) {
                    persistEditTarget()
                    editPointerId = -1
                    editTargetKey = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                editPointerId = -1
                editTargetKey = null
                rebuildLayout(width, height)
            }
        }
        scheduleRedraw()
        return true
    }

    fun toggleEditMode() = setEditMode(!editMode)
    fun isEditing(): Boolean = editMode

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        menuVisible = false
        editPointerId = -1
        editTargetKey = null
        if (!enabled) selectedEditKey = null
        releaseAll()
        if (enabled) showToast("EDITAR: arraste controles; selecione um e use TAM − / TAM +")
        scheduleRedraw()
    }

    private fun showStatusDialog() {
        val enabled = CheatStore.load(context, gameKey).count { it.enabled }
        val disk = NativeBridge.diskState()
        val diskLabel = when {
            disk.count > 1 -> "${disk.index + 1}/${disk.count}${if (disk.ejected) " • tampa aberta" else ""}"
            disk.count == 1 -> "1/1"
            else -> "indisponível"
        }
        AlertDialog.Builder(context)
            .setTitle(gameTitle)
            .setMessage("${NativeBridge.lastMessage()}\n\nPreset: ${config.overlayPreset.label}\nControle: ${config.analogMode.label}\nDisco: $diskLabel\nCheats ativos: $enabled")
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

    private fun showVisualDialog() {
        val labels = arrayOf(
            "Controles na tela",
            "Labels dos botões",
            "L1 / L2 / R1 / R2",
            "START / SELECT",
            "Setas do D-pad",
            "Modo limpo",
            "Opacidade dinâmica",
            "HUD de performance"
        )
        val checked = booleanArrayOf(
            config.controlsVisible, config.showLabels, config.showShoulders, config.showStartSelect,
            config.showDpad, config.cleanOverlay, config.dynamicOpacity, config.showPerformanceHud
        )
        AlertDialog.Builder(context)
            .setTitle("Visual • $gameTitle")
            .setMultiChoiceItems(labels, checked) { _, which, value ->
                config = when (which) {
                    0 -> config.copy(controlsVisible = value)
                    1 -> config.copy(showLabels = value)
                    2 -> config.copy(showShoulders = value)
                    3 -> config.copy(showStartSelect = value)
                    4 -> config.copy(showDpad = value)
                    5 -> config.copy(cleanOverlay = value)
                    6 -> config.copy(dynamicOpacity = value)
                    7 -> config.copy(showPerformanceHud = value)
                    else -> config
                }
                persistGameConfig()
                rebuildLayout(width, height)
                scheduleRedraw()
            }
            .setNeutralButton("Usar padrão global") { _, _ ->
                InputSettings.clearGameProfile(context, gameKey)
                config = InputSettings.resolve(context)
                rebuildLayout(width, height)
                scheduleRedraw()
            }
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showLayoutDialog() {
        val presets = InputSettings.OverlayPreset.entries
        val selected = presets.indexOf(config.overlayPreset).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Preset de layout")
            .setSingleChoiceItems(presets.map { "${it.label} — ${it.subtitle}" }.toTypedArray(), selected) { dialog, which ->
                val preset = presets[which]
                config = config.copy(
                    overlayPreset = preset,
                    cleanOverlay = preset != InputSettings.OverlayPreset.STANDARD,
                    showLabels = preset == InputSettings.OverlayPreset.STANDARD
                )
                persistGameConfig()
                InputSettings.resetControlPositions(context, gameKey)
                rebuildLayout(width, height)
                dialog.dismiss()
                showToast("Layout ${preset.label} aplicado a este jogo")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDiskDialog() {
        val state = NativeBridge.diskState()
        if (state.count <= 0) {
            showToast("O core não expôs controle de disco para esta sessão")
            return
        }
        if (state.count == 1) {
            AlertDialog.Builder(context)
                .setTitle("Disco • $gameTitle")
                .setMessage("Esta sessão possui apenas um disco. Em jogos multi-disc, o OmniCore mostrará todos os discos aqui sem reiniciar o jogo.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = Array(state.count) { index ->
            buildString {
                append("Disco ").append(index + 1)
                if (index == state.index) append(" • inserido")
            }
        }
        AlertDialog.Builder(context)
            .setTitle("Trocar disco • $gameTitle")
            .setSingleChoiceItems(labels, state.index) { dialog, which ->
                if (which == state.index) {
                    dialog.dismiss()
                    showToast("O disco ${which + 1} já está inserido")
                    return@setSingleChoiceItems
                }
                if (NativeBridge.setDiskIndex(which)) {
                    dialog.dismiss()
                    showToast("Trocando para o disco ${which + 1}…")
                    postDelayed({
                        val updated = NativeBridge.diskState()
                        if (updated.index == which) {
                            showToast("Disco ${which + 1}/${updated.count} inserido")
                        } else {
                            showToast(NativeBridge.lastMessage())
                        }
                    }, 450L)
                } else {
                    showToast("A troca de disco não está disponível agora")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCheatDialog() {
        val current = CheatStore.load(context, gameKey).toMutableList()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        lateinit var dialog: AlertDialog

        if (current.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "Nenhum cheat salvo para este jogo. Use códigos compatíveis com PS1/PCSX-ReARMed."
                setPadding(0, dp(8), 0, dp(12))
            })
        } else {
            current.forEach { cheat ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val check = CheckBox(context).apply {
                    text = cheat.name
                    isChecked = cheat.enabled
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, enabled ->
                        val idx = current.indexOfFirst { it.id == cheat.id }
                        if (idx >= 0) {
                            current[idx] = current[idx].copy(enabled = enabled)
                            CheatStore.save(context, gameKey, current)
                            applyStoredCheats(current)
                        }
                    }
                }
                val delete = Button(context).apply {
                    text = "×"
                    minWidth = 0
                    setOnClickListener {
                        current.removeAll { it.id == cheat.id }
                        CheatStore.save(context, gameKey, current)
                        applyStoredCheats(current)
                        dialog.dismiss()
                        showCheatDialog()
                    }
                }
                row.addView(check)
                row.addView(delete, LinearLayout.LayoutParams(dp(52), dp(44)))
                container.addView(row)
            }
        }

        val add = Button(context).apply { text = "+ Adicionar cheat" }
        val disable = Button(context).apply { text = "Desativar todos" }
        container.addView(add)
        container.addView(disable)
        val scroll = ScrollView(context).apply { addView(container) }
        dialog = AlertDialog.Builder(context)
            .setTitle("Cheats • $gameTitle")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .create()
        add.setOnClickListener { showAddCheatDialog(dialog) }
        disable.setOnClickListener {
            val disabled = current.map { it.copy(enabled = false) }
            CheatStore.save(context, gameKey, disabled)
            applyStoredCheats(disabled)
            dialog.dismiss()
            showCheatDialog()
        }
        dialog.show()
    }

    private fun showAddCheatDialog(parent: AlertDialog) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        val name = EditText(context).apply { hint = "Nome do cheat"; isSingleLine = true }
        val code = EditText(context).apply {
            hint = "Código (uma ou várias linhas)"
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(name)
        box.addView(code)
        AlertDialog.Builder(context)
            .setTitle("Adicionar cheat")
            .setView(box)
            .setMessage("Exemplo de formato: 80012345 0063. O código precisa corresponder à região/versão do jogo.")
            .setPositiveButton("Salvar") { _, _ ->
                val value = code.text.toString().trim()
                if (value.isBlank()) {
                    showToast("Informe um código")
                    return@setPositiveButton
                }
                val list = CheatStore.load(context, gameKey).toMutableList()
                list += CheatStore.Cheat(name = name.text.toString().trim().ifBlank { "Cheat ${list.size + 1}" }, code = value, enabled = true)
                CheatStore.save(context, gameKey, list)
                applyStoredCheats(list)
                parent.dismiss()
                showCheatDialog()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyStoredCheats(cheats: List<CheatStore.Cheat> = CheatStore.load(context, gameKey)) {
        if (!NativeBridge.isRunning()) return
        NativeBridge.resetCheats()
        cheats.forEachIndexed { index, cheat ->
            if (cheat.enabled && cheat.code.isNotBlank()) NativeBridge.setCheat(index, true, cheat.code)
        }
    }

    private fun persistGameConfig() {
        InputSettings.saveGameConfig(context, gameKey, config)
    }

    private fun adjustSelectedControlScale(delta: Float) {
        val key = selectedEditKey
        if (key == null) {
            showToast("Toque em um controle primeiro")
            return
        }
        val current = InputSettings.resolveControlScale(context, key, gameKey)
        val next = (current + delta).coerceIn(0.65f, 1.45f)
        InputSettings.saveControlScale(context, key, next, gameKey)
        rebuildLayout(width, height)
        scheduleRedraw()
    }

    private fun findEditTarget(x: Float, y: Float): String? {
        if (insideAnalog(x, y, 1.55f)) return "analog"
        return findButtonAt(x, y, 1.45f)?.key
    }

    private fun findButtonAt(x: Float, y: Float, hitScale: Float): Region? {
        var best: Region? = null
        var bestDistance = Double.MAX_VALUE
        for (region in regions) {
            if (!isRegionVisible(region) || !contains(region, x, y, hitScale)) continue
            val distance = hitDistance(region, x, y)
            if (distance < bestDistance) {
                best = region
                bestDistance = distance
            }
        }
        return best
    }

    private fun hitDistance(region: Region, x: Float, y: Float): Double {
        if (!region.wide) return hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) / region.radius.coerceAtLeast(1f)
        val nx = abs(x - region.cx) / (region.radius * 1.75f).coerceAtLeast(1f)
        val ny = abs(y - region.cy) / (region.radius * 0.92f).coerceAtLeast(1f)
        return hypot(nx.toDouble(), ny.toDouble())
    }

    private fun moveEditTarget(x: Float, y: Float) {
        val key = editTargetKey ?: return
        val margin = min(width, height) * 0.050f
        val nx = x.coerceIn(margin, width - margin)
        val ny = y.coerceIn(margin * 1.25f, height - margin)
        if (key == "analog") {
            analogCx = nx; analogCy = ny; analogKnobX = nx; analogKnobY = ny
        } else {
            regions = regions.map { if (it.key == key) it.copy(cx = nx, cy = ny) else it }
        }
        scheduleRedraw()
    }

    private fun persistEditTarget() {
        val key = editTargetKey ?: return
        if (width <= 0 || height <= 0) return
        if (key == "analog") {
            InputSettings.saveControlPosition(context, key, analogCx / width, analogCy / height, gameKey)
        } else {
            val region = regions.firstOrNull { it.key == key } ?: return
            InputSettings.saveControlPosition(context, key, region.cx / width, region.cy / height, gameKey)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun releaseAll() {
        stopTankAssistLoop(clearButtons = false)
        tankAssist.reset()
        buttonPointerTargets.clear()
        regionPressed = emptySet()
        analogDpadPressed = emptySet()
        commitButtons()
        analogPointerId = -1
        updateAnalog(analogCx, analogCy)
    }

    private fun isRegionVisible(region: Region): Boolean = when {
        region.id in 4..7 && !config.showDpad -> false
        region.id in 10..13 && !config.showShoulders -> false
        region.id in 2..3 && !config.showStartSelect -> false
        else -> true
    }

    private fun insideAnalog(x: Float, y: Float, scale: Float): Boolean =
        hypot((x - analogCx).toDouble(), (y - analogCy).toDouble()) <= analogRadius * scale

    private fun updateAnalog(x: Float, y: Float) {
        if (analogRadius <= 0f) return
        var dx = (x - analogCx) / analogRadius
        var dy = (y - analogCy) / analogRadius
        val magnitude = sqrt(dx * dx + dy * dy)
        if (magnitude > 1f) { dx /= magnitude; dy /= magnitude }
        val deadzone = 0.11f
        if (magnitude < deadzone) {
            dx = 0f; dy = 0f
        } else if (magnitude > 0f) {
            val remapped = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            val remapScale = remapped / magnitude.coerceAtLeast(0.0001f)
            dx *= remapScale; dy *= remapScale
        }
        analogKnobX = analogCx + dx * analogRadius * 0.72f
        analogKnobY = analogCy + dy * analogRadius * 0.72f
        val analogChanged = lastAnalogX.isNaN() || abs(dx - lastAnalogX) > 0.0025f || abs(dy - lastAnalogY) > 0.0025f
        val nextDigital = when (config.analogMode) {
            InputSettings.AnalogMode.NATIVE -> {
                if (analogChanged) NativeBridge.setAnalog(0, dx, dy)
                emptySet()
            }
            InputSettings.AnalogMode.DPAD -> {
                if (analogChanged) NativeBridge.setAnalog(0, 0f, 0f)
                dpadProjection(dx, dy)
            }
            InputSettings.AnalogMode.SMART -> {
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
        if (nextDigital != analogDpadPressed) {
            analogDpadPressed = nextDigital
            commitButtons()
        }
        lastAnalogX = dx
        lastAnalogY = dy
        scheduleRedraw()
    }

    private fun ensureTankAssistLoop() {
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
        val threshold = 0.42f
        var mask = 0
        if (y <= -threshold) mask = mask or 1
        if (y >= threshold) mask = mask or 2
        if (x <= -threshold) mask = mask or 4
        if (x >= threshold) mask = mask or 8
        return dpadProjectionCache[mask]
    }

    private fun contains(region: Region, x: Float, y: Float, hitScale: Float): Boolean = if (region.wide) {
        val halfW = region.radius * 1.75f * hitScale
        val halfH = region.radius * 0.92f * hitScale
        x in (region.cx - halfW)..(region.cx + halfW) && y in (region.cy - halfH)..(region.cy + halfH)
    } else {
        hypot((x - region.cx).toDouble(), (y - region.cy).toDouble()) <= region.radius * hitScale
    }

    private fun commitButtons() {
        val next = regionPressed + analogDpadPressed
        if (next == committedButtons) return
        (committedButtons - next).forEach { NativeBridge.setButton(it, false) }
        (next - committedButtons).forEach { NativeBridge.setButton(it, true) }
        committedButtons = next
        scheduleRedraw()
    }

    private fun showToast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun scheduleRedraw() { if (isAttachedToWindow) postInvalidateOnAnimation() else invalidate() }
}
