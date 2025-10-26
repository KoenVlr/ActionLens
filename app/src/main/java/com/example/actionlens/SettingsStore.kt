package com.example.actionlens

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Single source of truth for persistent settings.
 * Stores only *user selections*. Derived values (like maxDelay) are computed on demand.
 */
object SettingsStore {

    private const val PREFS_NAME = "actionlens_prefs"

    // Keys
    private const val K_WIDTH = "pref_width"
    private const val K_HEIGHT = "pref_height"
    private const val K_FPS = "pref_fps"
    private const val K_DELAY_SELECTED = "pref_delay_seconds_selected"
    private const val K_MIRROR = "pref_mirror_preview"
    private const val K_SHOW_LIVE_OVERLAY = "pref_show_live_overlay"
    private const val K_LIVE_OVERLAY_CORNER = "pref_live_overlay_corner"

    // Defaults
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_DELAY = 3
    private const val DEFAULT_MIRROR = false
    private const val DEFAULT_SHOW_LIVE = true
    private val DEFAULT_CORNER = LiveOverlayCorner.TOP_RIGHT

    /** Corner options for the live preview overlay */
    enum class LiveOverlayCorner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    data class Settings(
        val width: Int = DEFAULT_WIDTH,
        val height: Int = DEFAULT_HEIGHT,
        val fps: Int = DEFAULT_FPS,
        val delaySecondsSelected: Int = DEFAULT_DELAY,
        val mirrorPreview: Boolean = DEFAULT_MIRROR,
        val showLiveOverlay: Boolean = DEFAULT_SHOW_LIVE,
        val liveOverlayCorner: LiveOverlayCorner = DEFAULT_CORNER
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): Settings {
        val p = prefs(ctx)
        val cornerName = p.getString(K_LIVE_OVERLAY_CORNER, DEFAULT_CORNER.name)
        val corner = runCatching { LiveOverlayCorner.valueOf(cornerName!!) }
            .getOrDefault(DEFAULT_CORNER)

        return Settings(
            width = p.getInt(K_WIDTH, DEFAULT_WIDTH),
            height = p.getInt(K_HEIGHT, DEFAULT_HEIGHT),
            fps = p.getInt(K_FPS, DEFAULT_FPS),
            delaySecondsSelected = p.getInt(K_DELAY_SELECTED, DEFAULT_DELAY),
            mirrorPreview = p.getBoolean(K_MIRROR, DEFAULT_MIRROR),
            showLiveOverlay = p.getBoolean(K_SHOW_LIVE_OVERLAY, DEFAULT_SHOW_LIVE),
            liveOverlayCorner = corner
        )
    }

    fun save(ctx: Context, s: Settings) {
        prefs(ctx).edit {
            putInt(K_WIDTH, s.width)
            putInt(K_HEIGHT, s.height)
            putInt(K_FPS, s.fps)
            putInt(K_DELAY_SELECTED, s.delaySecondsSelected)
            putBoolean(K_MIRROR, s.mirrorPreview)
            putBoolean(K_SHOW_LIVE_OVERLAY, s.showLiveOverlay)
            putString(K_LIVE_OVERLAY_CORNER, s.liveOverlayCorner.name)
        }
    }
}
