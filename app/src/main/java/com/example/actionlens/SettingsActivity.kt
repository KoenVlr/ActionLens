package com.example.actionlens

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var delaySeekBar: SeekBar
    private lateinit var delayLabel: TextView
    private lateinit var showLiveSwitch: Switch
    private lateinit var livePositionButton: Button
    private lateinit var saveButton: Button

    private var width = 1280
    private var height = 720
    private var fps = 30
    private var delaySeconds = 3
    private var showLive = true
    private var liveCorner = SettingsStore.LiveOverlayCorner.TOP_RIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        resolutionSpinner = findViewById(R.id.spinnerResolution)
        fpsSpinner = findViewById(R.id.spinnerFps)
        delaySeekBar = findViewById(R.id.seekDelay)
        delayLabel = findViewById(R.id.textDelay)
        showLiveSwitch = findViewById(R.id.switchShowLive)
        livePositionButton = findViewById(R.id.btnLivePosition)
        saveButton = findViewById(R.id.btnSave)

        // --- Load saved settings ---
        val s = SettingsStore.load(this)
        width = s.width
        height = s.height
        fps = s.fps
        delaySeconds = s.delaySecondsSelected
        showLive = s.showLiveOverlay
        liveCorner = s.liveOverlayCorner

        // --- Resolution Spinner ---
        val resolutions = listOf("1280x720", "1920x1080", "640x480")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = resAdapter

        // --- FPS Spinner ---
        val fpsOptions = listOf(24, 30, 60)
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsOptions)
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = fpsAdapter

        // --- Restore selections ---
        resolutionSpinner.setSelection(resolutions.indexOf("${width}x$height").coerceAtLeast(0))
        fpsSpinner.setSelection(fpsOptions.indexOf(fps).coerceAtLeast(0))
        delaySeekBar.progress = delaySeconds
        delayLabel.text = "Delay: ${delaySeconds}s"

        // --- Initialize delay max based on memory ---
        updateDelayMax()

        // --- Restore live overlay settings ---
        showLiveSwitch.isChecked = showLive
        livePositionButton.isEnabled = showLive

        livePositionButton.text = "Live Position: ${cornerLabel(liveCorner)}"

        showLiveSwitch.setOnCheckedChangeListener { _, isChecked ->
            showLive = isChecked
            livePositionButton.isEnabled = isChecked
        }

        livePositionButton.setOnClickListener {
            val options = arrayOf("Top Left", "Top Right", "Bottom Left", "Bottom Right")
            val current = liveCorner.ordinal
            AlertDialog.Builder(this)
                .setTitle("Select Live Preview Position")
                .setSingleChoiceItems(options, current) { dialog, which ->
                    liveCorner = SettingsStore.LiveOverlayCorner.values()[which]
                    livePositionButton.text = "Live Position: ${options[which]}"
                    dialog.dismiss()
                }
                .show()
        }

        // --- Listeners for camera and delay settings ---
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val (w, h) = parseResolution(resolutions[pos])
                width = w
                height = h
                updateDelayMax()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                fps = fpsOptions[pos]
                updateDelayMax()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        delaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                delayLabel.text = "Delay: ${value}s"
                delaySeconds = value
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Save all settings ---
        saveButton.setOnClickListener {
            val sNew = SettingsStore.Settings(
                width = width,
                height = height,
                fps = fps,
                delaySecondsSelected = delaySeconds,
                mirrorPreview = false, // (keep your default)
                showLiveOverlay = showLive,
                liveOverlayCorner = liveCorner
            )
            SettingsStore.save(this, sNew)
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Updates the slider’s maximum based on current resolution & fps. */
    private fun updateDelayMax() {
        val maxDelay = DelayCalculator.maxDelaySeconds(this, width, height, fps)
        delaySeekBar.max = maxDelay

        if (delaySeconds > maxDelay) {
            delaySeconds = maxDelay
            delaySeekBar.progress = maxDelay
            delayLabel.text = "Delay: ${delaySeconds}s (clamped)"
            Toast.makeText(this, "Max delay limited to $maxDelay s", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseResolution(res: String): Pair<Int, Int> {
        val parts = res.split("x")
        return parts[0].toInt() to parts[1].toInt()
    }

    private fun cornerLabel(corner: SettingsStore.LiveOverlayCorner): String = when (corner) {
        SettingsStore.LiveOverlayCorner.TOP_LEFT -> "Top Left"
        SettingsStore.LiveOverlayCorner.TOP_RIGHT -> "Top Right"
        SettingsStore.LiveOverlayCorner.BOTTOM_LEFT -> "Bottom Left"
        SettingsStore.LiveOverlayCorner.BOTTOM_RIGHT -> "Bottom Right"
    }
}
