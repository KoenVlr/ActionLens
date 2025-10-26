package com.example.actionlens

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.widget.Toast
import com.example.actionlens.databinding.ActivityMainBinding

class OverlayManager(private val activity: MainActivity, private val binding: ActivityMainBinding) {

    private lateinit var groupResolution: RadioGroup
    private lateinit var groupFps: RadioGroup
    private lateinit var delaySeek: SeekBar
    private lateinit var delayLabel: TextView
    private lateinit var switchLive: Switch
    private lateinit var btnCorner: Button
    private lateinit var btnSave: Button

    private var width = 1280
    private var height = 720
    private var fps = 30
    private var delay = 3
    private var showLive = true
    private var liveCorner = SettingsStore.LiveOverlayCorner.TOP_RIGHT

    fun initOverlayControls() {
        groupResolution = activity.findViewById(R.id.groupResolution)
        groupFps = activity.findViewById(R.id.groupFps)
        delaySeek = activity.findViewById(R.id.overlaySeekDelay)
        delayLabel = activity.findViewById(R.id.overlayTextDelay)
        switchLive = activity.findViewById(R.id.overlaySwitchShowLive)
        btnCorner = activity.findViewById(R.id.overlayBtnLivePosition)
        btnSave = activity.findViewById(R.id.overlayBtnSave)

        groupResolution.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.btnRes720 -> { width = 1280; height = 720 }
                R.id.btnRes1080 -> { width = 1920; height = 1080 }
                R.id.btnRes480 -> { width = 640; height = 480 }
            }
            updateDelayMax()
        }

        groupFps.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.btnFps24 -> fps = 24
                R.id.btnFps30 -> fps = 30
                R.id.btnFps60 -> fps = 60
            }
            updateDelayMax()
        }

        delaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                delay = value
                delayLabel.text = "Delay: ${value}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchLive.setOnCheckedChangeListener { _, isChecked ->
            showLive = isChecked
            btnCorner.isEnabled = isChecked
        }

        btnCorner.setOnClickListener {
            liveCorner = nextCorner(liveCorner)
            btnCorner.text = cornerLabel(liveCorner)
        }

        btnSave.setOnClickListener {
            val sNew = SettingsStore.Settings(
                width = width,
                height = height,
                fps = fps,
                delaySecondsSelected = delay,
                mirrorPreview = false,
                showLiveOverlay = showLive,
                liveOverlayCorner = liveCorner
            )
            SettingsStore.save(activity, sNew)
            hideOverlay()
            activity.applySettingsIfChanged()
        }

        populateFromStore()
    }

    fun showOverlay() {
        populateFromStore()
        val overlay = binding.settingsOverlay
        val sheet = binding.settingsSheet

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        sheet.translationY = sheet.height.toFloat()

        overlay.animate().alpha(1f).setDuration(200).start()
        sheet.post {
            sheet.animate()
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        binding.overlayDismissArea.setOnClickListener {
            hideOverlay()
        }
    }

    fun hideOverlay() {
        val overlay = binding.settingsOverlay
        val sheet = binding.settingsSheet

        sheet.animate()
            .translationY(sheet.height.toFloat())
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { overlay.visibility = View.GONE }
                    .start()
            }.start()
    }

    fun handleRotation(corrected: Int) {
        val sheet = binding.settingsSheet
        val rootW = binding.root.width.toFloat()
        val rootH = binding.root.height.toFloat()

        sheet.pivotX = 0f
        sheet.pivotY = 0f
        sheet.translationX = 0f
        sheet.translationY = 0f

        when (corrected) {
            0 -> sheet.rotation = 0f
            90 -> { sheet.rotation = 90f; sheet.translationX = rootW - sheet.height }
            180 -> { sheet.rotation = 180f; sheet.translationX = rootW - sheet.width; sheet.translationY = rootH - sheet.height }
            270 -> { sheet.rotation = 270f; sheet.translationY = rootH - sheet.width }
        }
    }

    private fun populateFromStore() {
        val s = SettingsStore.load(activity)
        width = s.width
        height = s.height
        fps = s.fps
        delay = s.delaySecondsSelected
        showLive = s.showLiveOverlay
        liveCorner = s.liveOverlayCorner

        when ("${width}x${height}") {
            "1920x1080" -> groupResolution.check(R.id.btnRes1080)
            "1280x720" -> groupResolution.check(R.id.btnRes720)
            "640x480" -> groupResolution.check(R.id.btnRes480)
        }

        when (fps) {
            24 -> groupFps.check(R.id.btnFps24)
            30 -> groupFps.check(R.id.btnFps30)
            60 -> groupFps.check(R.id.btnFps60)
        }

        delaySeek.progress = delay
        delayLabel.text = "Delay: ${delay}s"
        updateDelayMax()

        switchLive.isChecked = showLive
        btnCorner.isEnabled = showLive
        btnCorner.text = cornerLabel(liveCorner)
    }

    private fun updateDelayMax() {
        val maxDelay = DelayCalculator.maxDelaySeconds(activity, width, height, fps)
        delaySeek.max = maxDelay
        if (delay > maxDelay) {
            delay = maxDelay
            delaySeek.progress = maxDelay
            delayLabel.text = "Delay: ${delay}s (clamped)"
            Toast.makeText(activity, "Max delay limited to $maxDelay s", Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextCorner(c: SettingsStore.LiveOverlayCorner): SettingsStore.LiveOverlayCorner =
        when (c) {
            SettingsStore.LiveOverlayCorner.TOP_LEFT -> SettingsStore.LiveOverlayCorner.TOP_RIGHT
            SettingsStore.LiveOverlayCorner.TOP_RIGHT -> SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT
            SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT -> SettingsStore.LiveOverlayCorner.BOTTOM_LEFT
            SettingsStore.LiveOverlayCorner.BOTTOM_LEFT -> SettingsStore.LiveOverlayCorner.TOP_LEFT
        }

    private fun cornerLabel(corner: SettingsStore.LiveOverlayCorner): String = when (corner) {
        SettingsStore.LiveOverlayCorner.TOP_LEFT -> "Top Left"
        SettingsStore.LiveOverlayCorner.TOP_RIGHT -> "Top Right"
        SettingsStore.LiveOverlayCorner.BOTTOM_LEFT -> "Bottom Left"
        SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT -> "Bottom Right"
    }
}
