package com.example.actionlens

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.actionlens.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var renderer: ActionLensRenderer? = null
    private var cameraController: CameraController? = null

    private val requiredPermissions = arrayOf(Manifest.permission.CAMERA)

    // Orientation handling for UI rotation
    private var orientationListener: OrientationEventListener? = null
    private var currentUiRotation = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        // 🔒 Lock screen orientation so system never flips the GL surface
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Placeholder: later this button will open the settings overlay
        binding.settingsButton.setOnClickListener {
            Toast.makeText(this, "Settings placeholder", Toast.LENGTH_SHORT).show()
        }

        // Surface lifecycle: start/stop renderer and camera
        binding.glSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startRendererAndCamera()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopRendererAndCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        })

        // Initialize orientation listener to rotate only the UI (not the feed)
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

    // --- Camera + Renderer setup ---

    private fun startRendererAndCamera() {
        if (!allPermissionsGranted()) {
            requestPermissions(requiredPermissions, 10)
            return
        }

        val width = 1280
        val height = 720
        val fps = 30
        val delay = 3

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

    // --- Permissions ---
    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- UI rotation helpers ---
    /** Snap sensor degrees to nearest 0/90/180/270 quadrant. */
    private fun quantizeDegrees(deg: Int): Int {
        return when {
            inRangeWrap(deg, 315, 360) || inRangeWrap(deg, 0, 45) -> 0
            inRangeWrap(deg, 45, 135) -> 90
            inRangeWrap(deg, 135, 225) -> 180
            else -> 270 // 225..315
        }
    }

    private fun inRangeWrap(v: Int, start: Int, end: Int): Boolean {
        return if (start <= end) (v in start..end) else (v >= start || v <= end)
    }

    /** Rotate UI elements (e.g. settings button) in the correct direction. */
    private fun rotateUiTo(angle: Int) {
        val corrected = (360 - angle) % 360 // flip direction for visual correctness
        binding.settingsButton.animate()
            .rotation(corrected.toFloat())
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // --- Lifecycle ---
    override fun onResume() {
        super.onResume()
        if (binding.glSurface.holder.surface.isValid && renderer == null) {
            startRendererAndCamera()
        }
        orientationListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener?.disable()
        stopRendererAndCamera()
    }
}
