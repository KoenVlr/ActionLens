package com.example.actionlens

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// EGL flag for ES 3.0 context
private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040

class ActionLensRenderer(
    private val surface: Surface,
    private val fps: Int = 30,
    private val delaySeconds: Int = 3,
    // Camera buffer size; keep this in sync with your CameraController setDefaultBufferSize().
    private val frameWidth: Int = 1280,
    private val frameHeight: Int = 720
) {
    fun pipCornerVisible(): Boolean = pipCorner != PipCorner.HIDDEN
    fun pipCornerOrdinal(): Int = pipCorner.ordinal

    private val TAG = "ActionLensRenderer"

    // --- EGL state ---
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // --- Camera input (OES) ---
    private var oesTex = 0
    lateinit var surfaceTex: SurfaceTexture
        private set
    private val stMatrix = FloatArray(16)
    @Volatile private var frameAvailable = false

    // --- Threading / lifecycle ---
    @Volatile private var shouldExit = false
    @Volatile private var running = false

    // Callback once SurfaceTexture is ready
    @Volatile var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // --- Delayed playback ring buffer ---
    private val delayFrames = (fps * delaySeconds).coerceAtLeast(1)
    private val ringTex = IntArray(delayFrames)
    private val ringFbo = IntArray(delayFrames)
    private var head = 0

    // Use atomic for safe reads from UI thread
    private val framesBufferedAtomic = AtomicInteger(0)

    // --- Live PiP config ---
    enum class PipCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, HIDDEN }
    @Volatile var pipCorner: PipCorner = PipCorner.TOP_RIGHT
    @Volatile var pipScale: Float = 0.25f

    /** Returns the SurfaceTexture that Camera2 should write into (OES). */
    fun getInputSurfaceTexture(): SurfaceTexture = surfaceTex

    /** 0.0 while buffer is empty, 1.0 once the delay buffer is full (playback-ready). */
    fun getFillRatio(): Float {
        val max = delayFrames
        val cur = framesBufferedAtomic.get().coerceAtMost(max)
        return if (max <= 0) 0f else cur.toFloat() / max.toFloat()
    }

    private var renderThread: Thread? = null

    fun start() {
        Log.d(TAG, "Starting renderer thread (fps=$fps, delay=${delaySeconds}s, size=${frameWidth}x$frameHeight)")
        shouldExit = false
        running = true
        renderThread = thread(start = true, name = "ActionLensRenderer") {
            renderLoop()
        }
    }

    fun stopBlocking() {
        Log.d(TAG, "stopBlocking() called")
        shouldExit = true
        val t = renderThread
        if (t != null && t.isAlive) {
            try {
                t.join(1500) // Wait for render thread to exit gracefully
                Log.d(TAG, "Renderer thread joined successfully")
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for render thread to finish")
            }
        }
        renderThread = null
    }

    fun stop() {
        // legacy non-blocking version for compatibility
        stopBlocking()
    }


    // --- Core render loop ---
    private fun renderLoop() {
        Log.d(TAG, "Render loop starting...")
        waitForValidSurface()
        initEGL()
        setupGL()

        Log.d(TAG, "EGL + GL setup complete. Entering main loop...")

        val progOes = GlUtil.buildProgram(VS, FS_OES)
        val progRgb = GlUtil.buildProgram(VS, FS_RGB)
        val quadVao = GlUtil.makeQuadVao()

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        var loopCount = 0L
        val startTime = System.currentTimeMillis()

        try {
            while (!shouldExit) {
                if (!surface.isValid) break

                // Frame capture
                if (frameAvailable) {
                    frameAvailable = false
                    surfaceTex.updateTexImage()
                    surfaceTex.getTransformMatrix(stMatrix)

                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ringFbo[head])
                    GLES30.glViewport(0, 0, frameWidth, frameHeight)
                    GlUtil.drawOes(progOes, quadVao, oesTex, stMatrix)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

                    head = (head + 1) % delayFrames
                    val cur = framesBufferedAtomic.get()
                    if (cur < delayFrames) framesBufferedAtomic.incrementAndGet()

                    if (loopCount % 30L == 0L) {
                        Log.d(TAG, "Frame buffered: $cur/$delayFrames (head=$head)")
                    }
                }

                val surfW = eglQuery(EGL14.EGL_WIDTH)
                val surfH = eglQuery(EGL14.EGL_HEIGHT)
                GLES30.glViewport(0, 0, surfW, surfH)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                // Draw delayed frame
                if (framesBufferedAtomic.get() >= delayFrames) {
                    val idx = (head - delayFrames + delayFrames) % delayFrames
                    GlUtil.drawTex(progRgb, quadVao, ringTex[idx])
                }

                // Live PiP
                if (pipCorner != PipCorner.HIDDEN) {
                    val pipW = (surfW * pipScale).toInt()
                    val pipH = (surfH * pipScale).toInt()
                    val margin = (8 * (surfW / 360f)).toInt().coerceAtLeast(8)
                    val (x, y) = when (pipCorner) {
                        PipCorner.TOP_LEFT -> Pair(margin, surfH - pipH - margin)
                        PipCorner.TOP_RIGHT -> Pair(surfW - pipW - margin, surfH - pipH - margin)
                        PipCorner.BOTTOM_LEFT -> Pair(margin, margin)
                        PipCorner.BOTTOM_RIGHT -> Pair(surfW - pipW - margin, margin)
                        PipCorner.HIDDEN -> Pair(0, 0)
                    }
                    GLES30.glViewport(x, y, pipW, pipH)
                    GlUtil.drawOes(progOes, quadVao, oesTex, stMatrix)
                }

                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                loopCount++
                if (loopCount % (fps * 3) == 0L) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    Log.d(TAG, "Loop alive: $loopCount frames (${elapsed}s elapsed, fill=${getFillRatio()})")
                }
            }
        } finally {
            Log.d(TAG, "Exiting render loop. Cleaning up EGL + GL resources.")
            destroyGLResources()
            destroyEGL()
            running = false
            Log.d(TAG, "Renderer thread stopped.")
        }
    }

    // --- Helpers ---

    private fun waitForValidSurface() {
        var tries = 0
        while (!surface.isValid && tries < 100) {
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
            tries++
        }
        if (!surface.isValid) {
            Log.e(TAG, "Surface invalid after waiting ($tries tries)")
            throw IllegalStateException("Surface is not valid after waiting.")
        } else {
            Log.d(TAG, "Surface became valid after $tries checks")
        }
    }

    private fun initEGL() {
        Log.d(TAG, "Initializing EGL...")
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val vMaj = IntArray(1); val vMin = IntArray(1)
        EGL14.eglInitialize(eglDisplay, vMaj, 0, vMin, 0)
        Log.d(TAG, "EGL initialized v${vMaj[0]}.${vMin[0]}")

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val cfg = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfg, 0, 1, num, 0)
        require(num[0] > 0) { "No EGL config found" }

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        require(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) { "Failed to create EGL context" }

        val surfAttrs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg[0], surface, surfAttrs, 0)
        require(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) { "Failed to create EGL window surface" }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        Log.d(TAG, "EGL context + surface created successfully.")
    }

    private fun setupGL() {
        Log.d(TAG, "Setting up GL resources...")
        oesTex = GlUtil.createOesTex()
        surfaceTex = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
            setOnFrameAvailableListener { frameAvailable = true }
        }
        Log.d(TAG, "OES texture + SurfaceTexture ready.")
        onSurfaceTextureReady?.invoke(surfaceTex)

        GLES30.glGenTextures(delayFrames, ringTex, 0)
        GLES30.glGenFramebuffers(delayFrames, ringFbo, 0)
        for (i in 0 until delayFrames) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ringTex[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, frameWidth, frameHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ringFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ringTex[i], 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            require(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "FBO incomplete at index $i, status=0x${Integer.toHexString(status)}"
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        framesBufferedAtomic.set(0)
        head = 0
        Log.d(TAG, "GL setup complete with $delayFrames delayed frames.")
    }

    private fun destroyGLResources() {
        Log.d(TAG, "Destroying GL resources...")
        try {
            if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting OES texture: ${e.message}")
        }
        try {
            if (delayFrames > 0) {
                GLES30.glDeleteTextures(delayFrames, ringTex, 0)
                GLES30.glDeleteFramebuffers(delayFrames, ringFbo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting ring textures/FBOs: ${e.message}")
        }
    }

    private fun destroyEGL() {
        Log.d(TAG, "Destroying EGL...")
        try {
            if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying EGL surface: ${e.message}")
        }
        try {
            if (eglDisplay != null && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying EGL context: ${e.message}")
        }
        EGL14.eglTerminate(eglDisplay)
        Log.d(TAG, "EGL terminated.")
    }

    private fun eglQuery(what: Int): Int {
        val out = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, out, 0)
        return out[0]
    }

    companion object {
        private const val VS = "#version 300 es\n" +
                "layout(location=0) in vec2 aPos;\n" +
                "layout(location=1) in vec2 aTex;\n" +
                "out vec2 vTex;\n" +
                "void main(){ vTex=aTex; gl_Position=vec4(aPos,0.0,1.0); }\n"

        private const val FS_OES = "#version 300 es\n" +
                "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                "precision mediump float;\n" +
                "in vec2 vTex;\n" +
                "uniform samplerExternalOES uTex;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "out vec4 fragColor;\n" +
                "void main(){ vec4 p=vec4(vTex,0.0,1.0); vec2 tc=(uTexMatrix*p).xy; fragColor=texture(uTex,tc); }\n"

        private const val FS_RGB = "#version 300 es\n" +
                "precision mediump float;\n" +
                "in vec2 vTex;\n" +
                "uniform sampler2D uTex;\n" +
                "out vec4 fragColor;\n" +
                "void main(){ fragColor=texture(uTex,vTex); }\n"
    }
}
