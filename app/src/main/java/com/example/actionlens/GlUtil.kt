package com.example.actionlens

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log

/** OpenGL utility helpers for ActionLensRenderer. */
object GlUtil {

    // --- Geometry: Fullscreen quad ---
    private val FULL_QUAD_POS = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f,  1f,
        1f,  1f
    )

    private val FULL_QUAD_TEX = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    // --- Utility functions ---

    /** Create external OES texture for camera input. */
    fun createOesTex(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return tex[0]
    }

    /** Build shader program from vertex + fragment source. */
    fun buildProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("Program link error: $info")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    /** Compile shader and throw on error. */
    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $info")
        }
        return shader
    }

    /** Create VAO with position and texcoord attributes bound. */
    fun makeQuadVao(): Int {
        val vao = IntArray(1)
        val vbo = IntArray(2)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, vbo, 0)

        GLES30.glBindVertexArray(vao[0])

        // Position VBO
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, FULL_QUAD_POS.size * 4, floatBuffer(FULL_QUAD_POS), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        // Texcoord VBO
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, FULL_QUAD_TEX.size * 4, floatBuffer(FULL_QUAD_TEX), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)

        return vao[0]
    }

    /** Draw OES (camera) texture with transform matrix applied. */
    fun drawOes(program: Int, vao: Int, tex: Int, texMatrix: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)

        val locTex = GLES30.glGetUniformLocation(program, "uTex")
        val locMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")

        // Bind OES texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES30.glUniform1i(locTex, 0)

        // Pass camera transform
        if (locMatrix >= 0) GLES30.glUniformMatrix4fv(locMatrix, 1, false, texMatrix, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    /** Draw standard 2D texture (for delayed frames). */
    fun drawTex(program: Int, vao: Int, tex: Int) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)

        val locTex = GLES30.glGetUniformLocation(program, "uTex")

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(locTex, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    // --- Internal utility ---

    private fun floatBuffer(data: FloatArray) =
        java.nio.ByteBuffer.allocateDirect(data.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(data)
                position(0)
            }
}
