package com.example.actionlens

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlin.math.floor
import kotlin.math.max

/**
 * Delay calculator using 98% of the session baseline available RAM.
 * Baseline is cached in `actionlens_session` to ensure consistent results
 * even after the renderer allocates memory.
 */
object DelayCalculator {

    fun maxDelaySeconds(
        context: Context,
        width: Int,
        height: Int,
        fps: Int
    ): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val totalMb = (mi.totalMem / (1024 * 1024)).toInt()
        val availMbNow = (mi.availMem / (1024 * 1024)).toInt()
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        val usedHeapMb =
            ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).toInt()

        // Read session baseline (set at app start)
        val sessionPrefs = context.getSharedPreferences("actionlens_session", Context.MODE_PRIVATE)
        val baselineAvailMb = sessionPrefs.getInt("baseline_avail_mb", -1)
        val usedAvailMb = if (baselineAvailMb > 0) baselineAvailMb else availMbNow

        Log.d("RAM", "========== Memory Info ==========")
        Log.d("RAM", "System total: ${totalMb} MB, available (now): ${availMbNow} MB")
        Log.d("RAM", "App heap limit: ${maxHeapMb} MB, heap used: ${usedHeapMb} MB")
        Log.d("RAM", "Low memory flag: ${mi.lowMemory}")
        Log.d("RAM", "Using available RAM: $usedAvailMb MB (baseline=${baselineAvailMb > 0})")

        // Use 98% of baseline (or fallback available) as buffer budget
        val bufferBudgetMb = max(256, (usedAvailMb * 0.98).toInt())
        Log.d("RAM", "Calculated buffer budget (98% of baseline/available): ${bufferBudgetMb} MB")

        // --- Frame memory + delay math ---
        val bytesPerPixel = 4L // RGBA8
        val bytesPerFrame = width.toLong() * height.toLong() * bytesPerPixel
        if (bytesPerFrame <= 0L || fps <= 0) return 1

        val effectiveBudgetBytes = bufferBudgetMb * 1_000_000L
        val maxFrames = floor(effectiveBudgetBytes.toDouble() / bytesPerFrame.toDouble()).toLong()
        val maxDelay = floor(maxFrames.toDouble() / fps.toDouble()).toInt()

        Log.d("DelayCalc", "Resolution: ${width}x$height @${fps}fps")
        Log.d("DelayCalc", "Bytes/frame: ${"%.4f".format(bytesPerFrame / 1_000_000.0)} MB")
        Log.d("DelayCalc", "Max frames: $maxFrames → ${maxDelay}s max delay (98% baseline)")
        Log.d("RAM", "=================================")

        return max(1, maxDelay)
    }
}
