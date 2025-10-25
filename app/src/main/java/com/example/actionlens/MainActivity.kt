package com.example.actionlens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.recreate
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var camera: CameraController? = null
    private var renderer: ActionLensRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val surfaceView = findViewById<SurfaceView>(R.id.glSurface)
        val settingsButton = findViewById<Button>(R.id.settingsButton)

        // Ask for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Step 1: Create renderer (owns GL + SurfaceTexture)
                renderer = ActionLensRenderer(holder.surface)

                // Step 2: Wait until renderer’s SurfaceTexture is ready before opening camera
                renderer!!.onSurfaceTextureReady = { st ->
                    camera = CameraController(this@MainActivity) { st }
                    camera!!.open()
                }

                // Step 3: Start renderer thread
                renderer!!.start()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                camera?.close()
                renderer?.stop()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        })

        // Optional Settings button for testing PiP controls
        settingsButton.setOnClickListener {
            renderer?.let {
                when (it.pipCorner) {
                    ActionLensRenderer.PipCorner.TOP_RIGHT ->
                        it.pipCorner = ActionLensRenderer.PipCorner.BOTTOM_LEFT
                    ActionLensRenderer.PipCorner.BOTTOM_LEFT ->
                        it.pipCorner = ActionLensRenderer.PipCorner.BOTTOM_RIGHT
                    ActionLensRenderer.PipCorner.BOTTOM_RIGHT ->
                        it.pipCorner = ActionLensRenderer.PipCorner.TOP_LEFT
                    ActionLensRenderer.PipCorner.TOP_LEFT ->
                        it.pipCorner = ActionLensRenderer.PipCorner.HIDDEN
                    ActionLensRenderer.PipCorner.HIDDEN ->
                        it.pipCorner = ActionLensRenderer.PipCorner.TOP_RIGHT
                }
                Toast.makeText(this, "PiP position: ${it.pipCorner}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onPause() {
        super.onPause()
        camera?.close()
        renderer?.stop()
    }
}
