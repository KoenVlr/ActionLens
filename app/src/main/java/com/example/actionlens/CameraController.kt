package com.example.actionlens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

class CameraController(
    private val context: Context,
    private val getSurfaceTexture: () -> android.graphics.SurfaceTexture
) {
    private val manager = context.getSystemService(Activity.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @SuppressLint("MissingPermission")
    fun open() {
        thread = HandlerThread("Camera2Thread").also { it.start() }
        handler = Handler(thread!!.looper)

        val cameraId = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                createSession(cam)
            }

            override fun onDisconnected(cam: CameraDevice) { cam.close() }
            override fun onError(cam: CameraDevice, err: Int) { cam.close() }
        }, handler)
    }

    private fun createSession(cam: CameraDevice) {
        val st = getSurfaceTexture.invoke()
        st.setDefaultBufferSize(1280, 720)
        val surface = Surface(st)

        cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(sess: CameraCaptureSession) {
                session = sess
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                req.addTarget(surface)
                req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                sess.setRepeatingRequest(req.build(), null, handler)
            }

            override fun onConfigureFailed(sess: CameraCaptureSession) {}
        }, handler)
    }

    fun close() {
        session?.close()
        device?.close()
        thread?.quitSafely()
    }
}
