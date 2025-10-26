package com.example.actionlens

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var delaySeekBar: SeekBar
    private lateinit var delayLabel: TextView
    private lateinit var saveButton: Button

    private var width = 1280
    private var height = 720
    private var fps = 30
    private var delaySeconds = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        resolutionSpinner = findViewById(R.id.spinnerResolution)
        fpsSpinner = findViewById(R.id.spinnerFps)
        delaySeekBar = findViewById(R.id.seekDelay)
        delayLabel = findViewById(R.id.textDelay)
        saveButton = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences("actionlens_prefs", MODE_PRIVATE)

        // Load current settings
        width = prefs.getInt("width", 1280)
        height = prefs.getInt("height", 720)
        fps = prefs.getInt("fps", 30)
        delaySeconds = prefs.getInt("delay", 3)

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

        // --- Initialize max slider value ---
        updateDelayMax()

        // --- Listeners ---
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

        saveButton.setOnClickListener {
            prefs.edit().apply {
                putInt("width", width)
                putInt("height", height)
                putInt("fps", fps)
                putInt("delay", delaySeconds)
                apply()
            }
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
}
