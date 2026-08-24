package com.omnicore.emulator.emulation

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.omnicore.emulator.core.nativebridge.NativeBridge
import com.omnicore.emulator.core.ps1.Ps1Core
import com.omnicore.emulator.model.GameEntry
import com.omnicore.emulator.performance.PerformanceManager
import com.omnicore.emulator.settings.InputSettings
import com.omnicore.emulator.settings.Ps1Settings
import com.omnicore.emulator.storage.Ps1Files
import com.omnicore.emulator.storage.Ps1BiosHealth
import com.omnicore.emulator.storage.Ps1MediaLayout
import com.omnicore.emulator.storage.Ps1PlaylistMedia
import com.omnicore.emulator.storage.Ps1DiscManifest
import com.omnicore.emulator.storage.SafGameSource
import java.io.File
import java.security.MessageDigest

class EmulationActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: GamepadOverlayView
    private lateinit var statusView: TextView
    private lateinit var presetView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var sessionDescriptors: List<ParcelFileDescriptor> = emptyList()
    private var sessionDir: File? = null
    private var sessionPersistent = false
    private var preparationThread: Thread? = null
    @Volatile private var destroyed = false
    private var started = false
    private var gamePath: String? = null
    private var gameKey: String = "game"
    private var gameTitle: String = "PlayStation"
    private lateinit var deviceProfile: PerformanceManager.DeviceProfile
    private lateinit var performanceConfig: PerformanceManager.RuntimeConfig
    private lateinit var ps1Config: Ps1Settings.Config
    private var thermalMonitor: ThermalMonitor? = null
    private var successfulPolls = 0
    private var lastRuntimeMessage = ""
    private var biosLabel = "HLE"

    private val physicalTankAssist = TankMovementAssist()
    private var physicalTankButtons: Set<Int> = emptySet()
    private var physicalTankGestureActive = false
    private var physicalTankLoopRunning = false
    private val physicalTankRunnable = object : Runnable {
        override fun run() {
            if (!physicalTankLoopRunning) return
            val input = InputSettings.resolveForGame(this@EmulationActivity, gameKey)
            if (!started || input.analogMode != InputSettings.AnalogMode.TANK_ASSIST || !physicalTankGestureActive) {
                stopPhysicalTankAssist(clearButtons = true)
                return
            }
            applyPhysicalTankButtons(physicalTankAssist.step(SystemClock.uptimeMillis()))
            handler.postDelayed(this, PHYSICAL_TANK_FRAME_MS)
        }
    }

    private val statusPoll = object : Runnable {
        override fun run() {
            if (started) {
                val text = NativeBridge.lastMessage()
                if (text.isNotBlank()) {
                    if (text != lastRuntimeMessage) {
                        lastRuntimeMessage = text
                        statusView.text = text
                        statusView.visibility = View.VISIBLE
                        successfulPolls = 0
                    }
                    if (text.startsWith("BOOT 6/6") || text.startsWith("RUN OK")) {
                        successfulPolls++
                        if (successfulPolls >= 7) statusView.visibility = View.GONE
                    } else if (text.startsWith("BOOT E") || text.startsWith("RUNTIME E")) {
                        statusView.visibility = View.VISIBLE
                        statusView.setBackgroundColor(Color.argb(225, 90, 15, 28))
                    } else if (text.startsWith("RUNTIME W")) {
                        statusView.visibility = View.VISIBLE
                        statusView.setBackgroundColor(Color.argb(220, 88, 58, 8))
                    }
                }
            }
            handler.postDelayed(this, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        deviceProfile = PerformanceManager.profile(this)
        performanceConfig = PerformanceManager.initialConfig(this)
        ps1Config = Ps1Settings.resolve(this)
        val biosHealth = Ps1BiosHealth.inspect(Ps1Files.systemDir(this))
        biosLabel = biosHealth.shortLabel
        registerThermalAdaptation()

        gameKey = intent.getStringExtra(EXTRA_GAME_ID).orEmpty().ifBlank { "game" }
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty().ifBlank { "PlayStation" }
        val uriString = intent.getStringExtra(EXTRA_GAME_URI)
        val extension = intent.getStringExtra(EXTRA_EXTENSION).orEmpty().lowercase()
        val folderUri = intent.getStringExtra(EXTRA_FOLDER_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val companionUris = intent.getStringArrayListExtra(EXTRA_COMPANION_URIS).orEmpty().map(Uri::parse)

        if (uriString.isNullOrBlank() || extension !in Ps1Core.SUPPORTED_EXTENSIONS) {
            Toast.makeText(this, "Arquivo de PS1 não suportado nesta versão.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()
        handler.post(statusPoll)
        prepareGameAsync(Uri.parse(uriString), extension, folderUri, companionUris)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(4, 5, 11)) }
        setContentView(root)

        surfaceView = SurfaceView(this).apply {
            // SurfaceView must remain a no-draw view. Giving it an opaque View
            // background clears PFLAG_SKIP_DRAW and can paint over the hole that
            // exposes the separate EGL surface. Letterboxing/background belongs
            // to the parent FrameLayout instead.
            setWillNotDraw(true)
            holder.addCallback(this@EmulationActivity)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        controls = GamepadOverlayView(this)
        root.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        statusView = TextView(this).apply {
            text = "Preparando $gameTitle…"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(Color.argb(190, 14, 15, 28))
            maxLines = 3
        }
        root.addView(statusView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(10) })

        presetView = TextView(this).apply {
            val inputMode = InputSettings.resolveForGame(this@EmulationActivity, gameKey).analogMode.label
            text = "${ps1Config.preset.label} • ${if (ps1Config.dualShock) "DualShock" else "Digital"} • $inputMode • $biosLabel"
            setTextColor(Color.argb(210, 226, 224, 255))
            textSize = 10f
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setBackgroundColor(Color.argb(120, 35, 32, 65))
        }
        root.addView(presetView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(8) })

        val save = actionButton("SALVAR") { NativeBridge.saveState(0); showTransientStatus("Solicitando save state…") }
        root.addView(save, FrameLayout.LayoutParams(dp(88), dp(38), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(10); topMargin = dp(10)
        })

        val load = actionButton("CARREGAR") { NativeBridge.loadState(0); showTransientStatus("Carregando save state…") }
        root.addView(load, FrameLayout.LayoutParams(dp(104), dp(38), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(104); topMargin = dp(10)
        })

        val diag = actionButton("STATUS") {
            statusView.text = NativeBridge.lastMessage()
            statusView.visibility = View.VISIBLE
            successfulPolls = 0
        }
        root.addView(diag, FrameLayout.LayoutParams(dp(88), dp(38), Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(98); topMargin = dp(10)
        })

        val exit = actionButton("SAIR") { finish() }
        root.addView(exit, FrameLayout.LayoutParams(dp(80), dp(38), Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(10); topMargin = dp(10)
        })

        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val (targetWidth, targetHeight) = when (ps1Config.aspectMode) {
                Ps1Settings.AspectMode.ORIGINAL_4_3 -> fitAspect(width, height, 4f / 3f)
                Ps1Settings.AspectMode.WIDE_16_9 -> fitAspect(width, height, 16f / 9f)
                Ps1Settings.AspectMode.FULLSCREEN -> width to height
            }
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                params.gravity = Gravity.CENTER
                surfaceView.layoutParams = params
            }
        }
    }

    private fun showTransientStatus(text: String) {
        statusView.text = text
        statusView.visibility = View.VISIBLE
        statusView.setBackgroundColor(Color.argb(190, 14, 15, 28))
        successfulPolls = 0
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 9f
        minWidth = 0
        minHeight = 0
        setPadding(dp(6), 0, dp(6), 0)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.argb(150, 28, 28, 48))
        alpha = 0.88f
        setOnClickListener { action() }
    }

    private data class PreparedContent(
        val path: String,
        val descriptors: List<ParcelFileDescriptor>,
        val sessionDir: File,
        val persistent: Boolean = false
    ) : AutoCloseable {
        override fun close() {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
            if (!persistent) runCatching { sessionDir.deleteRecursively() }
        }
    }

    private fun prepareGameAsync(uri: Uri, extension: String, folderUri: Uri?, companionUris: List<Uri>) {
        statusView.text = when (extension) {
            "cue" -> "PREP 1/3 • lendo CUE e faixas…"
            "ccd" -> "PREP 1/3 • lendo CCD e imagem…"
            "m3u" -> "PREP 1/3 • lendo playlist multi-disc…"
            else -> "PREP 1/3 • abrindo $gameTitle…"
        }
        preparationThread = Thread({
            val result = prepareSessionPath(uri, extension, folderUri, companionUris)
            handler.post {
                preparationThread = null
                if (destroyed) {
                    result.getOrNull()?.close()
                    return@post
                }
                result.onSuccess { prepared ->
                    sessionDescriptors = prepared.descriptors
                    sessionDir = prepared.sessionDir
                    sessionPersistent = prepared.persistent
                    gamePath = prepared.path
                    statusView.text = "PREP 3/3 • conteúdo pronto, iniciando core…"
                    if (surfaceView.holder.surface.isValid) tryStart(surfaceView.holder.surface)
                }.onFailure { error ->
                    statusView.text = "PREP E01 • ${error.message ?: "não consegui preparar o jogo"}"
                    statusView.setBackgroundColor(Color.argb(225, 90, 15, 28))
                    Toast.makeText(this, statusView.text, Toast.LENGTH_LONG).show()
                }
            }
        }, "OmniCore-ContentPrep").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun prepareSessionPath(uri: Uri, extension: String, folderUri: Uri?, companionUris: List<Uri>): Result<PreparedContent> =
        runCatching {
            when (extension) {
                "cue" -> prepareCueSession(uri, folderUri, companionUris)
                "ccd" -> prepareCcdSession(uri, folderUri, companionUris)
                "m3u" -> prepareM3uSession(uri, folderUri, companionUris)
                else -> prepareSingleFileSession(uri, extension)
            }
        }

    private fun prepareSingleFileSession(uri: Uri, extension: String): PreparedContent {
        val dir = freshSessionDir()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        return try {
            statusView.post { statusView.text = "PREP 2/3 • preparando acesso ao arquivo…" }
            val target = File(dir, "game.$extension")
            stageDocument(uri, target, descriptors)
            ensurePreparationActive()
            PreparedContent(target.absolutePath, descriptors.toList(), dir)
        } catch (error: Throwable) {
            descriptors.forEach { runCatching { it.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun prepareCueSession(cueUri: Uri, folderUri: Uri?, companionUris: List<Uri>): PreparedContent {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val dir = cueCacheDir()
        return try {
            ensurePreparationActive()
            val sources = if (folderUri != null) {
                SafGameSource.listDirectChildren(this, folderUri).filterNot { it.isDirectory }
            } else {
                (companionUris + cueUri).distinctBy(Uri::toString).map { SafGameSource.metadata(this, it) }
            }

            val cueText = SafGameSource.readCueText(this, cueUri)
            val references = SafGameSource.cueReferences(cueText)
            require(references.isNotEmpty()) { "O CUE não contém nenhuma linha FILE reconhecível." }

            val byName = sources.associateBy { it.name.lowercase() }
            val requiredTracks = references.map { reference ->
                val baseName = SafGameSource.normalizeReference(reference)
                byName[baseName.lowercase()]
                    ?: error("Faixa '$baseName' citada no CUE não encontrada. Importe a pasta completa.")
            }
            val auxiliaries = sources.filter { it.extension == "sbi" }
            val fingerprint = cueFingerprint(cueText, (requiredTracks + auxiliaries).distinctBy { it.uri.toString() })
            val marker = File(dir, ".source-fingerprint")
            val localCue = File(dir, "game.cue")

            val cacheValid = runCatching {
                marker.isFile && marker.readText(Charsets.UTF_8) == fingerprint &&
                    localCue.isFile && localCue.length() > 0L &&
                    validateCueSession(localCue).let { true }
            }.getOrDefault(false)

            if (cacheValid) {
                statusView.post { statusView.text = "PREP 2/3 • cache CUE/BIN validado — início rápido" }
                return PreparedContent(localCue.absolutePath, emptyList(), dir, persistent = true)
            }

            runCatching { dir.deleteRecursively() }
            require(dir.mkdirs() || dir.isDirectory) { "Não consegui criar o cache persistente do jogo." }
            statusView.post { statusView.text = "PREP 2/3 • preparando ${references.size} faixa(s) pela primeira vez…" }

            val resolvedNames = mutableMapOf<String, String>()
            requiredTracks.forEachIndexed { index, source ->
                ensurePreparationActive()
                val reference = references[index]
                val baseName = SafGameSource.normalizeReference(reference)
                val safeName = safeFileName(source.name)
                resolvedNames[baseName.lowercase()] = safeName
                stageDocument(source.uri, File(dir, safeName), descriptors, forceCopy = true)
            }

            auxiliaries.forEach { source ->
                stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors, forceCopy = true)
            }

            val rewrittenCue = SafGameSource.rewriteCueReferences(cueText, resolvedNames)
                .removePrefix("\uFEFF")
            localCue.writeText(rewrittenCue, Charsets.UTF_8)
            validateCueSession(localCue)
            marker.writeText(fingerprint, Charsets.UTF_8)
            ensurePreparationActive()
            PreparedContent(localCue.absolutePath, descriptors.toList(), dir, persistent = true)
        } catch (error: Throwable) {
            descriptors.forEach { runCatching { it.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun prepareCcdSession(ccdUri: Uri, folderUri: Uri?, companionUris: List<Uri>): PreparedContent {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val dir = freshSessionDir()
        return try {
            ensurePreparationActive()
            val sources = if (folderUri != null) {
                SafGameSource.listDirectChildren(this, folderUri).filterNot { it.isDirectory }
            } else {
                (companionUris + ccdUri).distinctBy(Uri::toString).map { SafGameSource.metadata(this, it) }
            }
            val plan = Ps1MediaLayout.plan(sources)
            val mediaSet = plan.sets.firstOrNull {
                it.kind == Ps1MediaLayout.Kind.CCD_SET && it.primary.uri.toString() == ccdUri.toString()
            } ?: error(
                plan.warnings.firstOrNull()
                    ?: "Não consegui montar o conjunto CCD/IMG. Importe a pasta completa do jogo."
            )
            val image = mediaSet.companions.firstOrNull { it.extension == "img" }
                ?: error("O CCD precisa do arquivo IMG correspondente.")

            statusView.post { statusView.text = "PREP 2/3 • preparando CCD/IMG${if (mediaSet.companions.any { it.extension == "sub" }) "/SUB" else ""}…" }
            val staged = (listOf(mediaSet.primary, image) + mediaSet.companions.filter { it.extension in setOf("sub", "sbi") })
                .distinctBy { it.uri.toString() }
            staged.forEach { source ->
                ensurePreparationActive()
                val extension = source.extension.lowercase()
                val target = File(dir, "game.$extension")
                stageDocument(source.uri, target, descriptors)
            }

            val localCcd = File(dir, "game.ccd")
            validateCcdSession(localCcd)
            ensurePreparationActive()
            PreparedContent(localCcd.absolutePath, descriptors.toList(), dir)
        } catch (error: Throwable) {
            descriptors.forEach { runCatching { it.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun prepareM3uSession(m3uUri: Uri, folderUri: Uri?, companionUris: List<Uri>): PreparedContent {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val dir = m3uCacheDir()
        return try {
            ensurePreparationActive()
            val sources = if (folderUri != null) {
                SafGameSource.listDirectChildren(this, folderUri).filterNot { it.isDirectory }
            } else {
                (companionUris + m3uUri).distinctBy(Uri::toString).map { SafGameSource.metadata(this, it) }
            }
            val playlist = sources.firstOrNull { it.uri.toString() == m3uUri.toString() }
                ?: SafGameSource.metadata(this, m3uUri)
            val plan = Ps1PlaylistMedia.plan(this, playlist, sources).getOrThrow()
            val playlistText = Ps1PlaylistMedia.readText(this, m3uUri)
            val fingerprint = cueFingerprint(playlistText, plan.stagingDocuments)
            val marker = File(dir, ".source-fingerprint")
            val localM3u = File(dir, "game.m3u")

            val cacheValid = runCatching {
                marker.isFile && marker.readText(Charsets.UTF_8) == fingerprint &&
                    localM3u.isFile && localM3u.length() > 0L &&
                    validateM3uSession(localM3u).let { true }
            }.getOrDefault(false)
            if (cacheValid) {
                statusView.post { statusView.text = "PREP 2/3 • cache multi-disc validado — início rápido" }
                return PreparedContent(localM3u.absolutePath, emptyList(), dir, persistent = true)
            }

            runCatching { dir.deleteRecursively() }
            require(dir.mkdirs() || dir.isDirectory) { "Não consegui criar o cache multi-disc." }
            statusView.post {
                statusView.text = "PREP 2/3 • preparando ${plan.manifest.discs.size} discos pela primeira vez…"
            }

            val payload = plan.stagingDocuments.filterNot { it.uri.toString() == m3uUri.toString() }
            val duplicateNames = payload.groupBy { safeFileName(it.name).lowercase() }
                .filterValues { docs -> docs.map { it.uri.toString() }.distinct().size > 1 }
            require(duplicateNames.isEmpty()) {
                "A playlist contém arquivos diferentes com o mesmo nome local: ${duplicateNames.keys.first()}."
            }

            payload.forEach { source ->
                ensurePreparationActive()
                stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors, forceCopy = true)
            }

            val rewritten = buildString {
                plan.manifest.discs.forEachIndexed { index, disc ->
                    disc.label?.takeIf { it.isNotBlank() }?.let { label ->
                        append("#EXTINF:-1,")
                            .append(label.replace('\r', ' ').replace('\n', ' '))
                            .append('\n')
                    }
                    append(safeFileName(plan.discDocuments[index].name)).append('\n')
                }
            }
            localM3u.writeText(rewritten, Charsets.UTF_8)
            validateM3uSession(localM3u)
            marker.writeText(fingerprint, Charsets.UTF_8)
            ensurePreparationActive()
            PreparedContent(localM3u.absolutePath, descriptors.toList(), dir, persistent = true)
        } catch (error: Throwable) {
            descriptors.forEach { runCatching { it.close() } }
            runCatching { dir.deleteRecursively() }
            throw error
        }
    }

    private fun validateM3uSession(m3uFile: File) {
        require(m3uFile.isFile && m3uFile.length() > 0L) { "A playlist M3U local ficou indisponível." }
        val manifest = Ps1DiscManifest.parseM3u(m3uFile.readText(Charsets.UTF_8))
        require(manifest.discs.size >= 2) { "A playlist local ficou sem pelo menos dois discos válidos." }
        manifest.discs.forEach { disc ->
            ensurePreparationActive()
            require('/' !in disc.reference && '\\' !in disc.reference) { "Referência insegura no M3U local: ${disc.reference}" }
            val image = File(m3uFile.parentFile, disc.reference)
            require(image.parentFile?.canonicalFile == m3uFile.parentFile?.canonicalFile) {
                "Referência externa no M3U local: ${disc.reference}"
            }
            require(image.isFile && image.length() > 0L) { "O disco '${disc.reference}' não ficou disponível no cache." }
            when (image.extension.lowercase()) {
                "cue" -> validateCueSession(image)
                "ccd" -> validateCcdSession(image)
                else -> runCatching {
                    java.io.RandomAccessFile(image, "r").use { file ->
                        val length = file.length()
                        require(length > 0L)
                        file.seek((length - 1L).coerceAtLeast(0L))
                        require(file.read() >= 0)
                    }
                }.getOrElse {
                    error("O disco '${disc.reference}' não aceita leitura aleatória necessária para emulação de CD.")
                }
            }
        }
    }

    private fun m3uCacheDir(): File {
        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir, "ps1-disc-cache/$safeKey-multidisc")
    }

    private fun cueCacheDir(): File {
        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir, "ps1-disc-cache/$safeKey")
    }

    private fun cueFingerprint(cueText: String, sources: List<SafGameSource.Document>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(cueText.toByteArray(Charsets.UTF_8))
        sources.sortedBy { it.name.lowercase() }.forEach { source ->
            digest.update(0.toByte())
            digest.update(source.name.lowercase().toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(source.sizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(source.lastModifiedMillis.toString().toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(source.uri.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun freshSessionDir(): File {
        val safeKey = gameKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(cacheDir, "ps1-session/$safeKey")
        dir.deleteRecursively()
        require(dir.mkdirs() || dir.isDirectory) { "Não consegui criar a sessão temporária." }
        return dir
    }

    private fun stageDocument(
        uri: Uri,
        target: File,
        retainedDescriptors: MutableList<ParcelFileDescriptor>,
        forceCopy: Boolean = false
    ) {
        ensurePreparationActive()
        target.parentFile?.mkdirs()
        target.delete()
        val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "r")) {
            "O Android não forneceu acesso a ${target.name}."
        }

        if (!forceCopy) {
            val procPath = "/proc/self/fd/${descriptor.fd}"
            val seekable = runCatching {
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                true
            }.getOrDefault(false)
            val linked = seekable && runCatching {
                Os.symlink(procPath, target.absolutePath)
                true
            }.getOrDefault(false)
            if (linked) {
                retainedDescriptors += descriptor
                return
            }
        }

        runCatching { descriptor.close() }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não consegui ler ${target.name}." }
            target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    ensurePreparationActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        require(target.isFile && target.length() > 0L) {
            "A faixa ${target.name} foi copiada vazia ou ficou inacessível."
        }
    }

    private fun validateCueSession(cueFile: File) {
        val cueText = cueFile.readText(Charsets.UTF_8)
        val references = SafGameSource.cueReferences(cueText)
        require(references.isNotEmpty()) { "O CUE local ficou sem referências FILE válidas." }

        references.forEach { reference ->
            ensurePreparationActive()
            val name = SafGameSource.normalizeReference(reference)
            val track = File(cueFile.parentFile, name)
            require(track.parentFile?.canonicalFile == cueFile.parentFile?.canonicalFile) {
                "Referência insegura no CUE: $name"
            }
            require(track.isFile && track.length() > 0L) {
                "A faixa '$name' não ficou disponível na sessão local."
            }
            runCatching {
                java.io.RandomAccessFile(track, "r").use { file ->
                    val length = file.length()
                    require(length > 0L)
                    file.seek((length - 1L).coerceAtLeast(0L))
                    require(file.read() >= 0)
                }
            }.getOrElse {
                error("A faixa '$name' não aceita leitura aleatória necessária para emulação de CD.")
            }
        }
    }

    private fun validateCcdSession(ccdFile: File) {
        require(ccdFile.isFile && ccdFile.length() > 0L) { "O descritor CCD ficou indisponível." }
        val image = File(ccdFile.parentFile, "${ccdFile.nameWithoutExtension}.img")
        require(image.exists() && image.length() > 0L) { "O IMG correspondente ao CCD ficou indisponível." }
        runCatching {
            java.io.RandomAccessFile(image, "r").use { file ->
                val length = file.length()
                require(length > 0L)
                file.seek((length - 1L).coerceAtLeast(0L))
                require(file.read() >= 0)
            }
        }.getOrElse {
            error("O IMG do conjunto CCD não aceita leitura aleatória necessária para emulação de CD.")
        }
        val sub = File(ccdFile.parentFile, "${ccdFile.nameWithoutExtension}.sub")
        if (sub.exists()) require(sub.length() > 0L) { "O SUB correspondente ao CCD está vazio." }
    }

    private fun safeFileName(name: String): String = name.replace('\\', '_').replace('/', '_').ifBlank { "track.bin" }

    private fun ensurePreparationActive() {
        if (destroyed || Thread.currentThread().isInterrupted) error("Preparação cancelada.")
    }

    private fun tryStart(surface: Surface) {
        if (started || !surface.isValid) return
        val path = gamePath ?: return
        val ok = NativeBridge.startPs1(
            gamePath = path,
            gameKey = gameKey,
            systemDir = Ps1Files.systemDir(this).absolutePath,
            saveDir = Ps1Files.saveDir(this).absolutePath,
            stateDir = Ps1Files.stateDir(this).absolutePath,
            surface = surface,
            performancePolicy = performanceConfig.policy.nativeValue,
            audioBufferBursts = performanceConfig.audioBufferBursts,
            tryExclusiveAudio = performanceConfig.tryExclusiveAudio,
            preferPowerEfficiency = performanceConfig.preferPowerEfficiency,
            aggressiveFramePacing = performanceConfig.aggressiveFramePacing,
            coreOptions = ps1Config.toCoreOptions(),
            dualShock = ps1Config.dualShock
        )
        started = ok
        if (!ok) {
            statusView.text = "BOOT E00 • o runtime nativo recusou iniciar a sessão"
            statusView.setBackgroundColor(Color.argb(225, 90, 15, 28))
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { tryStart(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopSession() }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        if (::surfaceView.isInitialized && surfaceView.holder.surface.isValid) tryStart(surfaceView.holder.surface)
    }

    override fun onPause() {
        stopPhysicalTankAssist(clearButtons = true)
        stopSession()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        preparationThread?.interrupt()
        preparationThread = null
        handler.removeCallbacks(statusPoll)
        stopPhysicalTankAssist(clearButtons = true)
        unregisterThermalAdaptation()
        stopSession()
        sessionDescriptors.forEach { runCatching { it.close() } }
        sessionDescriptors = emptyList()
        if (!sessionPersistent) runCatching { sessionDir?.deleteRecursively() }
        sessionDir = null
        sessionPersistent = false
        super.onDestroy()
    }

    private fun registerThermalAdaptation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        thermalMonitor = ThermalMonitor(this).also { it.register() }
    }

    private fun unregisterThermalAdaptation() {
        thermalMonitor?.unregister()
        thermalMonitor = null
    }

    private fun onThermalStatusChanged(status: Int) {
        val next = PerformanceManager.resolve(PerformanceManager.readUserMode(this), deviceProfile, status)
        if (next == performanceConfig) return
        performanceConfig = next
        if (started) {
            NativeBridge.updatePerformancePolicy(
                performancePolicy = next.policy.nativeValue,
                audioBufferBursts = next.audioBufferBursts,
                tryExclusiveAudio = next.tryExclusiveAudio,
                preferPowerEfficiency = next.preferPowerEfficiency,
                aggressiveFramePacing = next.aggressiveFramePacing
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private class ThermalMonitor(private val activity: EmulationActivity) {
        private val manager = activity.getSystemService(PowerManager::class.java)
        private val listener = PowerManager.OnThermalStatusChangedListener { activity.onThermalStatusChanged(it) }
        fun register() { runCatching { manager?.addThermalStatusListener(listener) } }
        fun unregister() { runCatching { manager?.removeThermalStatusListener(listener) } }
    }

    private fun stopSession() {
        stopPhysicalTankAssist(clearButtons = true)
        if (::controls.isInitialized) controls.releaseAll()
        if (started) NativeBridge.stop()
        started = false
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (started && isJoystick && event.action == MotionEvent.ACTION_MOVE) {
            val input = InputSettings.resolveForGame(this, gameKey)
            val lx = normalizedAxis(event, MotionEvent.AXIS_X)
            val ly = normalizedAxis(event, MotionEvent.AXIS_Y)
            val rx = normalizedAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
            val ry = normalizedAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)

            NativeBridge.setAnalog(1, rx, ry)
            when (input.analogMode) {
                InputSettings.AnalogMode.NATIVE -> {
                    stopPhysicalTankAssist(clearButtons = true)
                    NativeBridge.setAnalog(0, lx, ly)
                    clearPhysicalDpad()
                }
                InputSettings.AnalogMode.DPAD -> {
                    stopPhysicalTankAssist(clearButtons = true)
                    NativeBridge.setAnalog(0, 0f, 0f)
                    applyPhysicalDpadProjection(lx, ly)
                }
                InputSettings.AnalogMode.SMART -> {
                    stopPhysicalTankAssist(clearButtons = true)
                    NativeBridge.setAnalog(0, lx, ly)
                    applyPhysicalDpadProjection(lx, ly)
                }
                InputSettings.AnalogMode.TANK_ASSIST -> {
                    NativeBridge.setAnalog(0, 0f, 0f)
                    val magnitude = kotlin.math.hypot(lx.toDouble(), ly.toDouble()).toFloat()
                    if (magnitude >= PHYSICAL_TANK_DEADZONE) {
                        if (!physicalTankGestureActive) {
                            clearPhysicalDpad()
                            physicalTankGestureActive = true
                            physicalTankAssist.beginGesture(SystemClock.uptimeMillis())
                        }
                        physicalTankAssist.updateTarget(lx, ly)
                        applyPhysicalTankButtons(physicalTankAssist.step(SystemClock.uptimeMillis()))
                        ensurePhysicalTankLoop()
                    } else {
                        stopPhysicalTankAssist(clearButtons = true)
                    }
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun ensurePhysicalTankLoop() {
        if (physicalTankLoopRunning || !physicalTankGestureActive) return
        physicalTankLoopRunning = true
        handler.postDelayed(physicalTankRunnable, PHYSICAL_TANK_FRAME_MS)
    }

    private fun stopPhysicalTankAssist(clearButtons: Boolean) {
        physicalTankLoopRunning = false
        handler.removeCallbacks(physicalTankRunnable)
        physicalTankGestureActive = false
        physicalTankAssist.reset()
        if (clearButtons) applyPhysicalTankButtons(emptySet())
    }

    private fun applyPhysicalTankButtons(next: Set<Int>) {
        if (next == physicalTankButtons) return
        (physicalTankButtons - next).forEach { NativeBridge.setButton(it, false) }
        (next - physicalTankButtons).forEach { NativeBridge.setButton(it, true) }
        physicalTankButtons = next
    }

    private fun applyPhysicalDpadProjection(x: Float, y: Float) {
        val threshold = 0.42f
        NativeBridge.setButton(4, y <= -threshold)
        NativeBridge.setButton(5, y >= threshold)
        NativeBridge.setButton(6, x <= -threshold)
        NativeBridge.setButton(7, x >= threshold)
    }

    private fun clearPhysicalDpad() {
        NativeBridge.setButton(4, false)
        NativeBridge.setButton(5, false)
        NativeBridge.setButton(6, false)
        NativeBridge.setButton(7, false)
    }

    private fun normalizedAxis(event: MotionEvent, primary: Int, fallback: Int? = null): Float {
        fun value(axis: Int): Float? {
            val range = event.device?.getMotionRange(axis, event.source) ?: return null
            val raw = event.getAxisValue(axis)
            val flat = range.flat.coerceAtLeast(0.08f)
            if (kotlin.math.abs(raw) <= flat) return 0f
            val sign = if (raw < 0f) -1f else 1f
            val normalized = ((kotlin.math.abs(raw) - flat) / (1f - flat)).coerceIn(0f, 1f)
            return normalized * sign
        }
        return value(primary) ?: fallback?.let(::value) ?: 0f
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 4
            KeyEvent.KEYCODE_DPAD_DOWN -> 5
            KeyEvent.KEYCODE_DPAD_LEFT -> 6
            KeyEvent.KEYCODE_DPAD_RIGHT -> 7
            KeyEvent.KEYCODE_BUTTON_A -> 0
            KeyEvent.KEYCODE_BUTTON_X -> 1
            KeyEvent.KEYCODE_BUTTON_B -> 8
            KeyEvent.KEYCODE_BUTTON_Y -> 9
            KeyEvent.KEYCODE_BUTTON_SELECT -> 2
            KeyEvent.KEYCODE_BUTTON_START -> 3
            KeyEvent.KEYCODE_BUTTON_L1 -> 10
            KeyEvent.KEYCODE_BUTTON_R1 -> 11
            KeyEvent.KEYCODE_BUTTON_L2 -> 12
            KeyEvent.KEYCODE_BUTTON_R2 -> 13
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 14
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 15
            else -> null
        }
        if (id != null && started) {
            if (id in 4..7 && event.action == KeyEvent.ACTION_DOWN) stopPhysicalTankAssist(clearButtons = true)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> NativeBridge.setButton(id, true)
                KeyEvent.ACTION_UP -> NativeBridge.setButton(id, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun fitAspect(width: Int, height: Int, aspect: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || aspect <= 0f) return width to height
        val widthFromHeight = (height * aspect).toInt().coerceAtLeast(1)
        return if (widthFromHeight <= width) {
            widthFromHeight to height
        } else {
            width to (width / aspect).toInt().coerceAtLeast(1)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COPY_BUFFER_BYTES = 2 * 1024 * 1024
        private const val PHYSICAL_TANK_FRAME_MS = 16L
        private const val PHYSICAL_TANK_DEADZONE = 0.18f
        private const val EXTRA_GAME_URI = "gameUri"
        private const val EXTRA_GAME_ID = "gameId"
        private const val EXTRA_GAME_TITLE = "gameTitle"
        private const val EXTRA_EXTENSION = "extension"
        private const val EXTRA_FOLDER_URI = "folderUri"
        private const val EXTRA_COMPANION_URIS = "companionUris"

        fun intent(context: Context, game: GameEntry, extension: String): Intent =
            Intent(context, EmulationActivity::class.java).apply {
                putExtra(EXTRA_GAME_URI, game.uri)
                putExtra(EXTRA_GAME_ID, game.id)
                putExtra(EXTRA_GAME_TITLE, game.title)
                putExtra(EXTRA_EXTENSION, extension)
                putExtra(EXTRA_FOLDER_URI, game.folderUri)
                putStringArrayListExtra(EXTRA_COMPANION_URIS, ArrayList(game.companionUris))
            }
    }
}
