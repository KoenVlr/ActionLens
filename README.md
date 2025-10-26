# 🧭 Settings / Controls

1. **Separate Settings Activity** --> DONE

   * Basic options screen; persists prefs (delay, resolution, etc.).

2. **Adaptive Delay Slider** --> DONE

   * Recalculate **max delay** from resolution/FPS/memory; clamp value and update slider on resolution change.

3. **Transparent On-Screen Settings Overlay (toggleable)**

   * Non-blocking overlay shown over the live/delayed feed for quick tweaks (e.g., delay, mirror, resolution).
   * Opens/closes without interrupting rendering.

4. **More Settings**

   * Toggle live feed on/off, preview mirror, resolution presets (720p/1080p), “Restore defaults”.

# 🎥 Recording / Streaming Output

1. **Save Last 10 Seconds**

   * Ring buffer → export to MP4 on demand via MediaCodec.

2. **Stream with Screen Casting**

   * Use MediaProjection to mirror the rendered SurfaceView.

3. **Streaming Options**

   * Bitrate & resolution controls, toggle mic audio, choose target/app/endpoint.

# 🧩 Nice-to-have (later)

* Delay presets (short/medium/long)
* HUD: FPS/recording indicator
* GPU/memory monitor to refine adaptive limits
* Auto-save last session (instant replay mode)
