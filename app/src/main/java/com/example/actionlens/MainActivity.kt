package com.example.actionlens

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat
import com.example.actionlens.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var renderer: ActionLensRenderer? = null
    private var cameraController: CameraController? = null

    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)

    private var orientationListener: OrientationEventListener? = null
    private var currentUiRotation = -1

    // --- Memory logging ---
    private val memoryHandler = Handler(Looper.getMainLooper())
    private var memoryLoggerRunning = false

    // --- UI update handler ---
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isPlaybackStarted = false

    // Keep last-used settings
    private var lastWidth = 1280
    private var lastHeight = 720
    private var lastFps = 30
    private var lastDelaySec = 3

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

        // --- Settings button ---
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

    // --- Memory logger every 3 seconds ---
    private val memoryLogger = object : Runnable {
        override fun run() {
            logMemory()
            memoryHandler.postDelayed(this, 3000)
        }
    }

    private fun startMemoryLogger() {
        if (!memoryLoggerRunning) {
            memoryLoggerRunning = true
            memoryHandler.post(memoryLogger)
            Log.d("RAM", "Memory logger started.")
        }
    }

    private fun stopMemoryLogger() {
        if (memoryLoggerRunning) {
            memoryHandler.removeCallbacks(memoryLogger)
            memoryLoggerRunning = false
            Log.d("RAM", "Memory logger stopped.")
        }
    }

    private fun logMemory() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val availMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)
        val heapUsed =
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        val heapMax = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        Log.d("RAM_MONITOR", buildString {
            append("=== Memory Report ===\n")
            append("System total: $totalMb MB | Available: $availMb MB\n")
            append("Heap used: $heapUsed MB / $heapMax MB\n")
            append("Low memory flag: ${mi.lowMemory}\n")
            append("Renderer active: ${renderer != null}\n")
            append("=====================")
        })
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
                s.fps != lastFps || s.delaySecondsSelected != lastDelaySec

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
        renderer?.stop()
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

    // --- Rotate settings button + progress bar ---
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
    }

    override fun onResume() {
        super.onResume()
        if (binding.glSurface.holder.surface.isValid) {
            applySettingsIfChanged()
        }
        orientationListener?.enable()
        startMemoryLogger()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        stopRendererAndCamera()
        stopMemoryLogger()
    }
}
