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

    // Keep last-used settings to detect changes
    private var lastWidth = 1280
    private var lastHeight = 720
    private var lastFps = 30
    private var lastDelaySec = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Cache baseline available RAM once per app session (before renderer allocs)
        val sessionPrefs = getSharedPreferences("actionlens_session", MODE_PRIVATE)
        if (!sessionPrefs.contains("baseline_avail_mb")) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val baselineAvailMb = (mi.availMem / (1024 * 1024)).toInt()
            sessionPrefs.edit().putInt("baseline_avail_mb", baselineAvailMb).apply()
            Log.d("RAM", "Session baseline cached: $baselineAvailMb MB")
        }

        // --- UI setup ---
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.glSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                applySettingsIfChanged()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopRendererAndCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        })

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
        val lowMemory = mi.lowMemory
        val heapUsed =
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        val heapMax = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        Log.d("RAM_MONITOR", buildString {
            append("=== Memory Report ===\n")
            append("System total: $totalMb MB | Available: $availMb MB\n")
            append("Heap used: $heapUsed MB / $heapMax MB\n")
            append("Low memory flag: $lowMemory\n")
            append("Renderer active: ${renderer != null}\n")
            append("=====================")

        })
    }

    // --- Apply settings from SharedPreferences ---
    private fun applySettingsIfChanged() {
        if (!allPermissionsGranted()) {
            requestPermissions(requiredPermissions, 10)
            return
        }

        val prefs = getSharedPreferences("actionlens_prefs", MODE_PRIVATE)
        val width = prefs.getInt("width", 1280)
        val height = prefs.getInt("height", 720)
        val fps = prefs.getInt("fps", 30)
        val delay = prefs.getInt("delay", 3)

        val needRestart = renderer == null ||
                width != lastWidth || height != lastHeight ||
                fps != lastFps || delay != lastDelaySec

        if (needRestart) {
            lastWidth = width
            lastHeight = height
            lastFps = fps
            lastDelaySec = delay

            stopRendererAndCamera()
            startRendererAndCamera(width, height, fps, delay)

            Toast.makeText(this, "Settings applied: ${width}x$height @${fps}fps, ${delay}s delay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRendererAndCamera(width: Int, height: Int, fps: Int, delay: Int) {
        val surface = binding.glSurface.holder.surface
        renderer = ActionLensRenderer(surface, fps, delay, width, height).apply {
            onSurfaceTextureReady = { texture ->
                startCamera(texture, width, height)
            }
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

    private fun rotateUiTo(angle: Int) {
        val corrected = (360 - angle) % 360
        binding.settingsButton.animate()
            .rotation(corrected.toFloat())
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (binding.glSurface.holder.surface.isValid) {
            applySettingsIfChanged()
        }
        orientationListener?.enable()
        startMemoryLogger()   // ✅ start logging
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        stopRendererAndCamera()
        stopMemoryLogger()    // ✅ stop logging
    }
}
