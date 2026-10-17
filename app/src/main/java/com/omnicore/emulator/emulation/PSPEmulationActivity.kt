package com.omnicore.emulator.emulation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.LinearLayout
import android.app.AlertDialog
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.core.psp.PSPNativeBridge
import com.omnicore.emulator.core.psp.PSPRomPreparer
import com.omnicore.emulator.core.psp.PSPSmartProfile
import com.omnicore.emulator.model.ConsoleSystem
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.storage.PSPStorage
import com.omnicore.emulator.settings.PSPSettings
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/** Isolated PSP Activity; PPSSPP, EGL and audio run only in process :psp. */
class PSPEmulationActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: PSPGamepadOverlayView
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OmniCore-PSP").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }

    private lateinit var currentGame: GameEntry
    private lateinit var smartProfile: PSPSmartProfile.Profile
    @Volatile private var destroyed = false
    @Volatile private var activityResumed = false
    @Volatile private var surfaceReady = false
    @Volatile private var startupInProgress = false
    @Volatile private var started = false
    @Volatile private var manualPaused = false
    @Volatile private var controlsVisible = true
    @Volatile private var surfaceGeneration = 0
    private var statusVisible = true
    private var lastNativeMessage = ""
    private var storagePaths: PSPStorage.Paths? = null
    private var preparedRom: File? = null
    @Volatile private var preparationFinished = false

    private val statusPoll = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (started) {
                val message = PSPNativeBridge.lastMessage()
                if (message.isNotBlank() && message != lastNativeMessage) {
                    lastNativeMessage = message
                    statusView.text = message
                    statusView.setBackgroundColor(
                        if (isErrorMessage(message)) Color.argb(225, 104, 20, 30)
                        else Color.argb(165, 12, 16, 29)
                    )
                    if (isErrorMessage(message)) {
                        statusVisible = true
                        statusView.visibility = View.VISIBLE
                    }
                }
                if (!PSPNativeBridge.isRunning()) {
                    statusView.text = PSPNativeBridge.lastMessage().ifBlank { "Runtime PSP encerrado." }
                    statusView.visibility = View.VISIBLE
                    statusVisible = true
                }
            }
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        currentGame = gameFromIntent() ?: run {
            Toast.makeText(this, "Imagem PlayStation Portable inválida.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        smartProfile = PSPSmartProfile.select(this)
        buildUi()
        handler.post(statusPoll)
        prepareGameAsync(currentGame)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(this@PSPEmulationActivity)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        controls = PSPGamepadOverlayView(this)
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            text = "PSP • preparando ${currentGame.title}…"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(Color.argb(185, 12, 16, 29))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(8) }
        )

        val menuButton = TextView(this).apply {
            text = "⋮"
            contentDescription = "Menu do PSP"
            setTextColor(Color.WHITE)
            textSize = 25f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(72, 14, 18, 32))
            setOnClickListener { showQuickMenu(this) }
        }
        root.addView(
            menuButton,
            FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(7)
                rightMargin = dp(7)
            }
        )
    }

    private fun prepareGameAsync(game: GameEntry) {
        statusView.text = "PSP • preparando ROM e assets…"
        worker.execute {
            val result = runCatching {
                val paths = PSPStorage.prepare(this)
                val rom = PSPRomPreparer.prepare(this, game, paths).getOrThrow()
                paths to rom
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.onSuccess { (paths, rom) ->
                    storagePaths = paths
                    preparedRom = rom
                    preparationFinished = true
                    statusView.text = "PSP • aguardando superfície gráfica…"
                    attemptStart()
                }.onFailure { error ->
                    statusView.text = "PSP • falha na preparação\n${error.message ?: error.javaClass.simpleName}"
                    statusView.setBackgroundColor(Color.argb(225, 104, 20, 30))
                    statusVisible = true
                }
            }
        }
    }

    private fun attemptStart() {
        if (destroyed || !surfaceReady || !preparationFinished || startupInProgress || started) return
        val paths = storagePaths ?: return
        val rom = preparedRom ?: return
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return

        val generation = surfaceGeneration
        startupInProgress = true
        statusView.text = "PSP • ${smartProfile.label} (${smartProfile.internalResolution}×) • iniciando…"
        statusView.setBackgroundColor(Color.argb(185, 12, 16, 29))
        worker.execute {
            var success = false
            var failure = ""
            try {
                if (!PSPNativeBridge.hasCore()) {
                    failure = "PPSSPP v1.20.4 não está no pacote. Verifique a compilação do core."
                } else if (surface.isValid) {
                    success = PSPNativeBridge.start(
                        surface = surface,
                        romPath = rom.absolutePath,
                        systemDir = paths.system.absolutePath,
                        saveDir = paths.saves.absolutePath,
                        internalResolution = smartProfile.internalResolution
                    )
                    if (!success) failure = PSPNativeBridge.lastMessage()
                } else {
                    failure = "A superfície gráfica do PSP não está disponível."
                }
            } catch (error: Throwable) {
                failure = error.message ?: error.javaClass.simpleName
            }

            val staleSurface = destroyed || !surfaceReady || surfaceGeneration != generation
            if (success && staleSurface) {
                PSPNativeBridge.stop()
                success = false
            }
            if (success) PSPNativeBridge.setPaused(!activityResumed || manualPaused)

            runOnUiThread {
                startupInProgress = false
                if (destroyed) return@runOnUiThread
                started = success
                if (success) {
                    lastNativeMessage = PSPNativeBridge.lastMessage()
                    statusView.text = lastNativeMessage.ifBlank { "PPSSPP iniciado." }
                    statusView.setBackgroundColor(Color.argb(165, 12, 16, 29))
                } else if (staleSurface) {
                    statusView.text = "PSP • superfície recriada; retomando…"
                    if (surfaceReady) attemptStart()
                } else {
                    statusView.text = "PSP • falha ao iniciar\n${failure.ifBlank { "Confira a imagem e os assets PPSSPP." }}"
                    statusView.setBackgroundColor(Color.argb(225, 104, 20, 30))
                    statusVisible = true
                    statusView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showQuickMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_PAUSE, 0, if (manualPaused) "Continuar jogo" else "Pausar jogo")
            menu.add(0, MENU_CONTROLS, 1, if (controlsVisible) "Ocultar controles touch" else "Mostrar controles touch")
            menu.add(0, MENU_STATUS, 2, if (statusVisible) "Ocultar status" else "Mostrar status")
            menu.add(0, MENU_PROFILE, 3, "Perfil inteligente")
            menu.add(0, MENU_GRAPHICS, 4, "Configurações gráficas")
            menu.add(0, MENU_CONTROLS_SETTINGS, 5, "Configurar controles touch")
            menu.add(0, MENU_EXIT, 6, "Sair do jogo")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_PAUSE -> {
                        manualPaused = !manualPaused
                        controls.releaseAll()
                        PSPNativeBridge.releasePhysicalInput()
                        PSPNativeBridge.setPaused(manualPaused || !activityResumed)
                        Toast.makeText(
                            this@PSPEmulationActivity,
                            if (manualPaused) "PSP pausado" else "PSP continuando",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                    MENU_CONTROLS -> {
                        controlsVisible = !controlsVisible
                        if (!controlsVisible) controls.releaseAll()
                        controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
                        true
                    }
                    MENU_STATUS -> {
                        statusVisible = !statusVisible
                        statusView.visibility = if (statusVisible) View.VISIBLE else View.GONE
                        true
                    }
                    MENU_PROFILE -> {
                        Toast.makeText(
                            this@PSPEmulationActivity,
                            smartProfile.summary,
                            Toast.LENGTH_LONG
                        ).show()
                        true
                    }
                    MENU_GRAPHICS -> {
                        showGraphicsSettings()
                        true
                    }
                    MENU_CONTROLS_SETTINGS -> {
                        showControlsSettings()
                        true
                    }
                    MENU_EXIT -> {
                        finish()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showGraphicsSettings() {
        val options = arrayOf("Automático (perfil inteligente)", "Resolução interna 1× (480×272)", "Resolução interna 2× (960×544)")
        val current = PSPSettings.internalResolution(this, smartProfile.internalResolution)
        AlertDialog.Builder(this)
            .setTitle("Gráficos do PSP")
            .setSingleChoiceItems(options, if (current == 1) 1 else 2) { dialog, which ->
                val selected = when (which) {
                    0 -> PSPSmartProfile.select(this).internalResolution
                    1 -> 1
                    else -> 2
                }
                PSPSettings.saveInternalResolution(this, selected)
                Toast.makeText(this, "A resolução será aplicada na próxima inicialização do jogo.", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showControlsSettings() {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val opacityLabel = TextView(this).apply { textSize = 14f }
        val opacity = SeekBar(this).apply {
            max = 80
            progress = PSPSettings.controlOpacity(this@PSPEmulationActivity) - 20
        }
        val sizeLabel = TextView(this).apply { textSize = 14f }
        val size = SeekBar(this).apply {
            max = 70
            progress = PSPSettings.controlSize(this@PSPEmulationActivity) - 70
        }
        val smart = android.widget.CheckBox(this).apply {
            text = "Opacidade inteligente (mais discretos quando não usados)"
            isChecked = PSPSettings.smartOpacity(this@PSPEmulationActivity)
        }
        fun updateLabels() {
            opacityLabel.text = "Opacidade: ${opacity.progress + 20}%"
            sizeLabel.text = "Tamanho dos controles: ${size.progress + 70}%"
        }
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateLabels()
        layout.addView(opacityLabel)
        layout.addView(opacity)
        layout.addView(sizeLabel)
        layout.addView(size)
        layout.addView(smart)
        AlertDialog.Builder(this)
            .setTitle("Controles touch do PSP")
            .setView(layout)
            .setPositiveButton("Salvar") { _, _ ->
                val opacityValue = opacity.progress + 20
                val sizeValue = size.progress + 70
                val smartValue = smart.isChecked
                PSPSettings.saveControlOpacity(this, opacityValue)
                PSPSettings.saveControlSize(this, sizeValue)
                PSPSettings.saveSmartOpacity(this, smartValue)
                controls.applyControlSettings(opacityValue, sizeValue, smartValue)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isGamepadSource(event.source)) {
            val button = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> 0
                KeyEvent.KEYCODE_BUTTON_B -> 8
                KeyEvent.KEYCODE_BUTTON_X -> 1
                KeyEvent.KEYCODE_BUTTON_Y -> 9
                KeyEvent.KEYCODE_BUTTON_L1 -> 10
                KeyEvent.KEYCODE_BUTTON_R1 -> 11
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_Z -> 2
                KeyEvent.KEYCODE_BUTTON_START -> 3
                KeyEvent.KEYCODE_DPAD_UP -> 4
                KeyEvent.KEYCODE_DPAD_DOWN -> 5
                KeyEvent.KEYCODE_DPAD_LEFT -> 6
                KeyEvent.KEYCODE_DPAD_RIGHT -> 7
                else -> -1
            }
            if (button >= 0) {
                PSPNativeBridge.setPhysicalButton(
                    button, event.deviceId, event.keyCode, event.action == KeyEvent.ACTION_DOWN
                )
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE && isGamepadSource(event.source)) {
            val x = shapeAxis(event.getAxisValue(MotionEvent.AXIS_X))
            val y = shapeAxis(event.getAxisValue(MotionEvent.AXIS_Y))
            PSPNativeBridge.setPhysicalAnalog(event.deviceId, x, y)
            PSPNativeBridge.setPhysicalHat(
                event.deviceId,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun shapeAxis(value: Float): Float {
        val clamped = value.coerceIn(-1f, 1f)
        val absolute = abs(clamped)
        return if (absolute <= ANALOG_DEADZONE) 0f else
            (if (clamped < 0f) -1f else 1f) *
                ((absolute - ANALOG_DEADZONE) / (1f - ANALOG_DEADZONE)).coerceIn(0f, 1f)
    }

    private fun isGamepadSource(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceGeneration++
        surfaceReady = holder.surface.isValid
        attemptStart()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = holder.surface.isValid
        attemptStart()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceGeneration++
        surfaceReady = false
        started = false
        runCatching {
            worker.execute {
                PSPNativeBridge.stop()
                PSPNativeBridge.releasePhysicalInput()
            }
        }
    }

    override fun onPause() {
        activityResumed = false
        if (::controls.isInitialized) controls.releaseAll()
        PSPNativeBridge.releasePhysicalInput()
        if (started) PSPNativeBridge.setPaused(true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        enterImmersiveMode()
        if (started) PSPNativeBridge.setPaused(manualPaused)
        else if (::surfaceView.isInitialized) attemptStart()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::controls.isInitialized) controls.releaseAll()
        runCatching {
            worker.execute {
                PSPNativeBridge.releasePhysicalInput()
                PSPNativeBridge.stop()
            }
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun gameFromIntent(): GameEntry? {
        val id = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty()
        val uri = intent.getStringExtra(EXTRA_GAME_URI).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        if (id.isBlank() || uri.isBlank() || fileName.isBlank()) return null
        val size = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
        if (size < 0L) return null
        return GameEntry(
            id = id,
            title = title.ifBlank { fileName.substringBeforeLast('.') },
            fileName = fileName,
            uri = uri,
            system = ConsoleSystem.PSP,
            sizeBytes = size,
            folderUri = null,
            companionUris = emptyList()
        )
    }

    private fun isErrorMessage(message: String): Boolean {
        val value = message.lowercase()
        return "falha" in value || "não foi possível" in value || "incompatível" in value ||
            "recusou" in value || "encerramento" in value || "indisponível" in value ||
            "não está no pacote" in value
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATUS_POLL_MS = 800L
        private const val ANALOG_DEADZONE = 0.10f
        private const val MENU_PAUSE = 1
        private const val MENU_CONTROLS = 2
        private const val MENU_STATUS = 3
        private const val MENU_PROFILE = 4
        private const val MENU_GRAPHICS = 5
        private const val MENU_CONTROLS_SETTINGS = 6
        private const val MENU_EXIT = 7

        private const val EXTRA_GAME_ID = "psp_game_id"
        private const val EXTRA_GAME_TITLE = "psp_game_title"
        private const val EXTRA_GAME_URI = "psp_game_uri"
        private const val EXTRA_FILE_NAME = "psp_file_name"
        private const val EXTRA_SIZE_BYTES = "psp_size_bytes"

        fun intent(context: Context, game: GameEntry): Intent =
            Intent(context, PSPEmulationActivity::class.java).apply {
                putExtra(EXTRA_GAME_ID, game.id)
                putExtra(EXTRA_GAME_TITLE, game.title)
                putExtra(EXTRA_GAME_URI, game.uri)
                putExtra(EXTRA_FILE_NAME, game.fileName)
                putExtra(EXTRA_SIZE_BYTES, game.sizeBytes)
            }
    }
}
