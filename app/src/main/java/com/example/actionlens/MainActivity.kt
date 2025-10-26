package com.example.actionlens

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.actionlens.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var renderer: ActionLensRenderer? = null
    private var cameraController: CameraController? = null
    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
    private var orientationListener: OrientationEventListener? = null
    private var currentUiRotation = -1
    // --- UI update handler ---
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isPlaybackStarted = false
    // Keep last-used settings
    private var lastWidth = 1280
    private var lastHeight = 720
    private var lastFps = 30
    private var lastDelaySec = 3
    // Overlay state
    private var overlayVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.delayProgressBar.pivotX = 0f
        binding.delayProgressBar.pivotY = 0f

        // ✅ Cache baseline available RAM once per app session
        val sessionPrefs = getSharedPreferences("actionlens_session", MODE_PRIVATE)
        if (!sessionPrefs.contains("baseline_avail_mb")) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val baselineAvailMb = (mi.availMem / (1024 * 1024)).toInt()
            sessionPrefs.edit().putInt("baseline_avail_mb", baselineAvailMb).apply()
            Log.d("RAM", "Session baseline cached: $baselineAvailMb MB")
        }

        // --- Settings button now toggles overlay ---
        binding.settingsButton.setOnClickListener {
            toggleSettingsOverlay()
        }

        // --- Surface callbacks ---
        binding.glSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                applySettingsIfChanged()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopRendererAndCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        })

        // --- Orientation listener ---
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val target = quantizeDegrees(orientation)
                if (target != currentUiRotation) {
                    currentUiRotation = target
                    rotateUiTo(target)
                }
            }
        }

        // 🔹 Start updating progress bar continuously
        uiHandler.post(delayUiRunnable)

        // 🔹 Prepare overlay controls with saved settings
        initOverlayControls()
    }

    // --- Smooth delay bar update loop ---
    private var displayedRatio = 0f
    private val updateIntervalMs = 16L // ~60fps

    private val delayUiRunnable = object : Runnable {
        override fun run() {
            val targetRatio = renderer?.getFillRatio() ?: 0f
            displayedRatio += (targetRatio - displayedRatio) * 0.15f

            if (!isPlaybackStarted) {
                if (binding.delayProgressBar.visibility != View.VISIBLE) {
                    binding.delayProgressBar.visibility = View.VISIBLE
                    binding.delayProgressBar.alpha = 1f
                }

                binding.delayProgressBar.progress = (displayedRatio * 1000).toInt()

                if (targetRatio >= 1f && displayedRatio > 0.99f) {
                    isPlaybackStarted = true
                    binding.delayProgressBar.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction {
                            binding.delayProgressBar.visibility = View.GONE
                            binding.delayProgressBar.alpha = 1f
                            binding.delayProgressBar.progress = 0
                            displayedRatio = 0f
                        }
                        .start()
                }
            }

            uiHandler.postDelayed(this, updateIntervalMs)
        }
    }

    // --- Apply settings ---
    private fun applySettingsIfChanged() {
        if (!allPermissionsGranted()) {
            requestPermissions(requiredPermissions, 10)
            return
        }

        val s = SettingsStore.load(this)
        val needRestart = renderer == null ||
                s.width != lastWidth || s.height != lastHeight ||
                s.fps != lastFps || s.delaySecondsSelected != lastDelaySec ||
                s.showLiveOverlay != renderer?.pipCornerVisible() ||     // 🔹 new line
                s.liveOverlayCorner.ordinal != renderer?.pipCornerOrdinal() // 🔹 new line

        if (needRestart) {
            lastWidth = s.width
            lastHeight = s.height
            lastFps = s.fps
            lastDelaySec = s.delaySecondsSelected

            stopRendererAndCamera()
            startRendererAndCamera(s)

            isPlaybackStarted = false
            binding.delayProgressBar.visibility = View.VISIBLE
            binding.delayProgressBar.progress = 0
            binding.delayProgressBar.alpha = 1f

            Toast.makeText(
                this,
                "Settings applied: ${s.width}x${s.height} @${s.fps}fps, ${s.delaySecondsSelected}s delay",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun startRendererAndCamera(s: SettingsStore.Settings) {
        val surface = binding.glSurface.holder.surface
        renderer = ActionLensRenderer(surface, s.fps, s.delaySecondsSelected, s.width, s.height).apply {
            pipCorner = if (!s.showLiveOverlay) {
                ActionLensRenderer.PipCorner.HIDDEN
            } else {
                when (s.liveOverlayCorner) {
                    SettingsStore.LiveOverlayCorner.TOP_LEFT -> ActionLensRenderer.PipCorner.TOP_LEFT
                    SettingsStore.LiveOverlayCorner.TOP_RIGHT -> ActionLensRenderer.PipCorner.TOP_RIGHT
                    SettingsStore.LiveOverlayCorner.BOTTOM_LEFT -> ActionLensRenderer.PipCorner.BOTTOM_LEFT
                    SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT -> ActionLensRenderer.PipCorner.BOTTOM_RIGHT
                }
            }

            onSurfaceTextureReady = { texture -> startCamera(texture, s.width, s.height) }
            start()
        }
    }

    private fun startCamera(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        cameraController?.close()
        cameraController = CameraController(this) { surfaceTexture }
        cameraController?.open()
    }

    private fun stopRendererAndCamera() {
        cameraController?.close()
        cameraController = null

        renderer?.let {
            Log.d("MainActivity", "Stopping renderer safely...")
            it.stopBlocking()  // ensures EGL is destroyed after loop exits
        }
        renderer = null
    }


    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun quantizeDegrees(deg: Int): Int {
        return when {
            inRangeWrap(deg, 315, 360) || inRangeWrap(deg, 0, 45) -> 0
            inRangeWrap(deg, 45, 135) -> 90
            inRangeWrap(deg, 135, 225) -> 180
            else -> 270
        }
    }

    private fun inRangeWrap(v: Int, start: Int, end: Int): Boolean {
        return if (start <= end) (v in start..end) else (v >= start || v <= end)
    }

    // --- Rotate settings button + progress bar + settings sheet ---
    private fun rotateUiTo(angle: Int) {
        val corrected = (360 - angle) % 360

        // Animate button rotation
        binding.settingsButton.animate()
            .rotation(corrected.toFloat())
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Progress bar positioning
        val bar = binding.delayProgressBar
        bar.pivotX = 0f
        bar.pivotY = 0f
        bar.translationX = 0f
        bar.translationY = 0f

        val w = bar.width.toFloat()
        val h = bar.height.toFloat()
        val rootW = binding.root.width.toFloat()
        val rootH = binding.root.height.toFloat()

        when (corrected) {
            0 -> { bar.rotation = 0f }
            90 -> { bar.rotation = 90f; bar.translationX = rootW - h }
            180 -> { bar.rotation = 180f; bar.translationX = rootW - w; bar.translationY = rootH - h }
            270 -> { bar.rotation = 270f; bar.translationY = rootH - w }
        }

        // Settings sheet positions so that it always slides from the visual bottom edge
        positionSettingsSheetForRotation(corrected)
    }

    private fun positionSettingsSheetForRotation(corrected: Int) {
        val sheet = binding.settingsSheet
        val rootW = binding.root.width.toFloat()
        val rootH = binding.root.height.toFloat()

        // Reset base
        sheet.pivotX = 0f
        sheet.pivotY = 0f
        sheet.translationX = 0f
        sheet.translationY = 0f

        when (corrected) {
            0 -> {
                sheet.rotation = 0f
                // anchored to bottom via layout_gravity; slide on Y
            }
            90 -> {
                sheet.rotation = 90f
                // Move to right edge (visual bottom after rotation)
                sheet.translationX = rootW - sheet.height.toFloat()
            }
            180 -> {
                sheet.rotation = 180f
                // Move to top edge
                sheet.translationX = rootW - sheet.width.toFloat()
                sheet.translationY = rootH - sheet.height.toFloat()
            }
            270 -> {
                sheet.rotation = 270f
                // Move to left edge
                sheet.translationY = rootH - sheet.width.toFloat()
            }
        }

        // If overlay is visible, make sure it's fully on-screen after rotation
        if (overlayVisible) {
            setSheetProgress(1f, animate = false)
        } else {
            setSheetProgress(0f, animate = false)
        }
    }

    // --- Overlay: show/hide with slide from bottom ---
    private fun toggleSettingsOverlay() {
        if (overlayVisible) hideSettingsOverlay() else showSettingsOverlay()
    }

    private fun showSettingsOverlay() {
        overlayVisible = true

        // Load current settings into controls
        populateOverlayFromStore()

        val overlay = findViewById<View>(R.id.settingsOverlay)
        val sheet = findViewById<View>(R.id.settingsSheet)

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        sheet.translationY = sheet.height.toFloat()

        // Animate overlay fade + sheet slide up
        overlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()

        sheet.post {
            sheet.animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Tap outside to dismiss
        findViewById<View>(R.id.overlayDismissArea).setOnClickListener {
            hideSettingsOverlay()
        }
    }

    private fun hideSettingsOverlay() {
        overlayVisible = false

        val overlay = findViewById<View>(R.id.settingsOverlay)
        val sheet = findViewById<View>(R.id.settingsSheet)

        // Animate out
        sheet.animate()
            .translationY(sheet.height.toFloat())
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        overlay.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }

    // Optional helper (for rotation logic compatibility)
    private fun setSheetProgress(progress: Float, animate: Boolean, end: (() -> Unit)? = null) {
        val sheet = findViewById<View>(R.id.settingsSheet)
        val ty = sheet.height * (1f - progress)
        if (animate) {
            sheet.animate()
                .translationY(ty)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { end?.invoke() }
                .start()
        } else {
            sheet.translationY = ty
            end?.invoke()
        }
    }

    // -------- Overlay controls logic (ported from SettingsActivity) --------

    private lateinit var overlayResolutionSpinner: Spinner
    private lateinit var overlayFpsSpinner: Spinner
    private lateinit var overlayDelaySeekBar: SeekBar
    private lateinit var overlayDelayLabel: TextView
    private lateinit var overlayShowLiveSwitch: Switch
    private lateinit var overlayLivePositionButton: Button
    private lateinit var overlaySaveButton: Button
    private lateinit var overlayCloseButton: Button

    private var ovWidth = 1280
    private var ovHeight = 720
    private var ovFps = 30
    private var ovDelaySeconds = 3
    private var ovShowLive = true
    private var ovLiveCorner = SettingsStore.LiveOverlayCorner.TOP_RIGHT

    private fun initOverlayControls() {
        val groupResolution: RadioGroup = findViewById(R.id.groupResolution)
        val groupFps: RadioGroup = findViewById(R.id.groupFps)
        overlayDelaySeekBar = findViewById(R.id.overlaySeekDelay)
        overlayDelayLabel = findViewById(R.id.overlayTextDelay)
        overlayShowLiveSwitch = findViewById(R.id.overlaySwitchShowLive)
        overlayLivePositionButton = findViewById(R.id.overlayBtnLivePosition)
        overlaySaveButton = findViewById(R.id.overlayBtnSave)

        // Resolution
        groupResolution.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.btnRes720 -> { ovWidth = 1280; ovHeight = 720 }
                R.id.btnRes1080 -> { ovWidth = 1920; ovHeight = 1080 }
                R.id.btnRes480 -> { ovWidth = 640; ovHeight = 480 }
            }
            updateDelayMax()
        }

        // FPS
        groupFps.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.btnFps24 -> ovFps = 24
                R.id.btnFps30 -> ovFps = 30
                R.id.btnFps60 -> ovFps = 60
            }
            updateDelayMax()
        }

        // Delay
        overlayDelaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                ovDelaySeconds = value
                overlayDelayLabel.text = "Delay: ${value}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Live preview switch ---
        overlayShowLiveSwitch.setOnCheckedChangeListener { _, isChecked ->
            ovShowLive = isChecked
            overlayLivePositionButton.isEnabled = isChecked
        }

        // Looping through corners
        overlayLivePositionButton.setOnClickListener {
            val next = when (ovLiveCorner) {
                SettingsStore.LiveOverlayCorner.TOP_LEFT -> SettingsStore.LiveOverlayCorner.TOP_RIGHT
                SettingsStore.LiveOverlayCorner.TOP_RIGHT -> SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT
                SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT -> SettingsStore.LiveOverlayCorner.BOTTOM_LEFT
                SettingsStore.LiveOverlayCorner.BOTTOM_LEFT -> SettingsStore.LiveOverlayCorner.TOP_LEFT
            }
            ovLiveCorner = next
            overlayLivePositionButton.text = cornerLabel(next)
        }

        // Save
        overlaySaveButton.setOnClickListener {
            val sNew = SettingsStore.Settings(
                width = ovWidth,
                height = ovHeight,
                fps = ovFps,
                delaySecondsSelected = ovDelaySeconds,
                mirrorPreview = false,
                showLiveOverlay = ovShowLive,
                liveOverlayCorner = ovLiveCorner
            )
            SettingsStore.save(this, sNew)
            hideSettingsOverlay()
            applySettingsIfChanged()
        }

        // Populate saved values
        populateOverlayFromStore()
    }


    private fun populateOverlayFromStore() {
        val s = SettingsStore.load(this)
        ovWidth = s.width
        ovHeight = s.height
        ovFps = s.fps
        ovDelaySeconds = s.delaySecondsSelected
        ovShowLive = s.showLiveOverlay
        ovLiveCorner = s.liveOverlayCorner

        // Resolution preselect
        val groupResolution: RadioGroup = findViewById(R.id.groupResolution)
        when ("${ovWidth}x${ovHeight}") {
            "1920x1080" -> groupResolution.check(R.id.btnRes1080)
            "1280x720" -> groupResolution.check(R.id.btnRes720)
            "640x480" -> groupResolution.check(R.id.btnRes480)
        }

        // FPS preselect
        val groupFps: RadioGroup = findViewById(R.id.groupFps)
        when (ovFps) {
            24 -> groupFps.check(R.id.btnFps24)
            30 -> groupFps.check(R.id.btnFps30)
            60 -> groupFps.check(R.id.btnFps60)
        }

        // Delay and switch
        overlayDelaySeekBar.progress = ovDelaySeconds
        overlayDelayLabel.text = "Delay: ${ovDelaySeconds}s"
        updateDelayMax()

        overlayShowLiveSwitch.isChecked = ovShowLive
        overlayLivePositionButton.isEnabled = ovShowLive
        overlayLivePositionButton.text = "${cornerLabel(ovLiveCorner)}"
    }

    /** Updates the slider’s maximum based on current resolution & fps. */
    private fun updateDelayMax() {
        val maxDelay = DelayCalculator.maxDelaySeconds(this, ovWidth, ovHeight, ovFps)
        overlayDelaySeekBar.max = maxDelay

        if (ovDelaySeconds > maxDelay) {
            ovDelaySeconds = maxDelay
            overlayDelaySeekBar.progress = maxDelay
            overlayDelayLabel.text = "Delay: ${ovDelaySeconds}s (clamped)"
            Toast.makeText(this, "Max delay limited to $maxDelay s", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseResolution(res: String): Pair<Int, Int> {
        val parts = res.split("x")
        return parts[0].toInt() to parts[1].toInt()
    }

    private fun cornerLabel(corner: SettingsStore.LiveOverlayCorner): String = when (corner) {
        SettingsStore.LiveOverlayCorner.TOP_LEFT -> "Top Left"
        SettingsStore.LiveOverlayCorner.TOP_RIGHT -> "Top Right"
        SettingsStore.LiveOverlayCorner.BOTTOM_LEFT -> "Bottom Left"
        SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT -> "Bottom Right"
    }

    // ---------------- Lifecycle ----------------

    override fun onResume() {
        super.onResume()
        if (binding.glSurface.holder.surface.isValid) {
            applySettingsIfChanged()
        }
        orientationListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        stopRendererAndCamera()
    }
}