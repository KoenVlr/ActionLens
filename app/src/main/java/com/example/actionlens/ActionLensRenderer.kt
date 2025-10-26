package com.example.actionlens

import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// EGL flag for ES 3.0 context
private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040

class ActionLensRenderer(
    private val surface: Surface,
    private val fps: Int = 30,
    private val delaySeconds: Int = 3,
    private val frameWidth: Int = 1280,
    private val frameHeight: Int = 720
) {

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

    // --- Optional progress callback (UI use) ---
    @Volatile var onProgressUpdate: ((Float) -> Unit)? = null

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

    // --- Public API ---

    /** Returns the SurfaceTexture that Camera2 should write into (OES). */
    fun getInputSurfaceTexture(): SurfaceTexture = surfaceTex

    /** 0.0 while buffer is empty, 1.0 once the delay buffer is full (playback-ready). */
    fun getFillRatio(): Float {
        val max = delayFrames
        val cur = framesBufferedAtomic.get().coerceAtMost(max)
        return if (max <= 0) 0f else cur.toFloat() / max.toFloat()
    }

    /** Convenience: true when delayed playback has enough frames to start. */
    fun isPlaybackStarted(): Boolean = framesBufferedAtomic.get() >= delayFrames

    fun start() {
        shouldExit = false
        running = true
        thread(start = true, name = "ActionLensRenderer") { renderLoop() }
    }

    /** Request graceful stop; actual EGL teardown happens on render thread. */
    fun stop() {
        shouldExit = true
    }

    // --- Core render loop ---
    private fun renderLoop() {
        waitForValidSurface()
        initEGL()
        setupGL()

        val progOes = GlUtil.buildProgram(VS, FS_OES)
        val progRgb = GlUtil.buildProgram(VS, FS_RGB)
        val quadVao = GlUtil.makeQuadVao()

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        try {
            while (!shouldExit) {
                if (!surface.isValid) break

                // --- Capture new frame ---
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

                    // ✅ Notify progress (0.0–1.0)
                    onProgressUpdate?.invoke(getFillRatio())
                }

                // --- Clear window ---
                val surfW = eglQuery(EGL14.EGL_WIDTH)
                val surfH = eglQuery(EGL14.EGL_HEIGHT)
                GLES30.glViewport(0, 0, surfW, surfH)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                // --- Draw delayed full-screen once buffer full ---
                if (framesBufferedAtomic.get() >= delayFrames) {
                    val idx = (head - delayFrames + delayFrames) % delayFrames
                    GLES30.glViewport(0, 0, surfW, surfH)
                    GlUtil.drawTex(progRgb, quadVao, ringTex[idx])
                }

                // --- Live PiP overlay ---
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

                // --- Present frame ---
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        } finally {
            destroyGLResources()
            destroyEGL()
            running = false
        }
    }

    // --- Helpers ---

    /** Block briefly until Surface becomes valid to avoid early EGL failures. */
    private fun waitForValidSurface() {
        var tries = 0
        while (!surface.isValid && tries < 100) {
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
            tries++
        }
        if (!surface.isValid) throw IllegalStateException("Surface is not valid after waiting.")
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val vMaj = IntArray(1)
        val vMin = IntArray(1)
        EGL14.eglInitialize(eglDisplay, vMaj, 0, vMin, 0)

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
        require(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT)

        val surfAttrs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg[0], surface, surfAttrs, 0)
        require(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupGL() {
        // Create OES texture + SurfaceTexture (camera writes here)
        oesTex = GlUtil.createOesTex()
        surfaceTex = SurfaceTexture(oesTex).apply {
            setDefaultBufferSize(frameWidth, frameHeight)
            setOnFrameAvailableListener { frameAvailable = true }
        }
        onSurfaceTextureReady?.invoke(surfaceTex)

        // Create full-resolution RGB textures + FBOs for delayed frames
        GLES30.glGenTextures(delayFrames, ringTex, 0)
        GLES30.glGenFramebuffers(delayFrames, ringFbo, 0)
        for (i in 0 until delayFrames) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ringTex[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                frameWidth, frameHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ringFbo[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, ringTex[i], 0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            require(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "FBO incomplete at index $i, status=0x${Integer.toHexString(status)}"
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // Reset counters
        framesBufferedAtomic.set(0)
        head = 0
    }

    private fun destroyGLResources() {
        try {
            if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        } catch (_: Exception) {}
        try {
            if (delayFrames > 0) {
                GLES30.glDeleteTextures(delayFrames, ringTex, 0)
                GLES30.glDeleteFramebuffers(delayFrames, ringFbo, 0)
            }
        } catch (_: Exception) {}
    }

    private fun destroyEGL() {
        try {
            if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
        } catch (_: Exception) {}
        try {
            if (eglDisplay != null && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
        } catch (_: Exception) {}
        EGL14.eglTerminate(eglDisplay)
    }

    private fun eglQuery(what: Int): Int {
        val out = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, out, 0)
        return out[0]
    }

    // --- Shaders ---
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