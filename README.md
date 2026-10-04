# Infinity Media Player for Android

[![JitPack](https://jitpack.io/v/asheeshsahu7300/infinity-media-player.svg)](https://jitpack.io/#asheeshsahu7300/infinity-media-player)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-24-brightgreen.svg)](https://developer.android.com)
[![Media3](https://img.shields.io/badge/Media3-1.4.1-orange.svg)](https://developer.android.com/media/media3)
[![ONNX Runtime](https://img.shields.io/badge/ONNX%20Runtime-NNAPI%20%7C%20ARM-blueviolet.svg)](https://onnxruntime.ai/)

An all-in-one, resilient Android Media Player library combining Google Media3 (ExoPlayer) with an embedded NVC-Live Neural Video Latent Concealer (ONNX Runtime / Android NNAPI), a specialized Audio Safety Controller with dynamic Qualcomm ACDB mitigation, first-class audio track switching without video pipeline resets, and PTS-synchronized subtitle rendering.

---

## Architecture Overview

```
Infinity Media Player
 |
 |-- core/
 |    |-- InfinityPlayer.kt             Unified player orchestrator & public API
 |    |-- InfinityPlayerConfig.kt       Configuration builder (buffers, audio mode, languages)
 |    |-- InfinityLoadControl.kt        Low-hysteresis anti-stall buffer controller
 |    |-- InfinityMediaSourceFactory.kt MPEG-TS and OkHttp live streaming factory
 |    `-- InfinityPlayerListener.kt     Lifecycle, telemetry, and track callbacks
 |
 |-- audio/
 |    |-- InfinityAudioTrack.kt         Audio track model (id, language, codec, channels)
 |    |-- AudioOutputMode.kt            AUTO, STEREO_PCM, MULTICHANNEL_PCM, PASSTHROUGH
 |    |-- AudioSafetyController.kt      Hardware detector & dynamic Qualcomm ACDB mitigation
 |    `-- AudioTelemetry.kt             Real-time audio diagnostics (underruns, latency, ACDB safety)
 |
 |-- subtitle/
 |    |-- InfinitySubtitleTrack.kt      Subtitle model (id, language, forced, CC, default)
 |    `-- Subtitle timeline sync        PTS-synchronized cue rendering via SubtitleView
 |
 |-- codec/
 |    |-- NvcNeuralConcealer.kt         ONNX Runtime NNAPI/ARM latent frame concealer
 |    `-- NvcTelemetry.kt               Real-time neural diagnostics model
 |
 `-- ui/
      `-- InfinityPlayerView.kt         Media3 Surface/Texture wrapper with SubtitleView
```

---

## Features

### 1. Audio Engine and Safety Controller
- **First-Class Audio Track Switching**:
  - Dynamically discovers all audio tracks (`InfinityAudioTrack`) with language, codec, channel count, sample rate, and flags.
  - Switches audio tracks seamlessly via Media3 `TrackSelectionOverride` without interrupting video rendering or re-buffering network streams.
- **Audio Safety Layer & Output Modes**:
  - `AudioOutputMode.AUTO`: Automatically detects Qualcomm Snapdragon SoCs and Dirac equalizer services (OnePlus, Oppo, Realme, Xiaomi). Falls back to safe 16-bit 48kHz Stereo PCM to prevent ACDB HAL crash loops (`acdb_loader_adsp_set_audio_cal` error `result=-100` on topology `0x10012d00`).
  - `AudioOutputMode.STEREO_PCM`: Forced stereo downmix for maximum compatibility.
  - `AudioOutputMode.MULTICHANNEL_PCM`: Uncompressed 5.1/7.1 software decoding.
  - `AudioOutputMode.PASSTHROUGH`: Direct bitstream passthrough (AC-3, E-AC3, DTS) for external HDMI AVR sound systems.
- **Audio Telemetry (V2)**:
  - Continuously monitors active audio codec, sample rate, channel count, active decoder name, audio buffer underruns, and ACDB safety intervention count.

### 2. Subtitle Engine
- **PTS-Synchronized Timeline**:
  - Directly maps subtitle cues to playback position (`currentPosition`) instead of timer delays.
  - Supports WebVTT, SRT, TTML, and SSA/ASS streams.
- **Track Selection and Metadata**:
  - Preserves closed-caption (`isClosedCaption`) and forced (`isForced`) attributes.
  - Automatic language preference: Forced -> Preferred Language -> Default Track -> Disabled.

### 3. NVC-Live Neural Video Concealment
- **Hardware Acceleration**:
  - Embedded ONNX Runtime utilizing Android NNAPI (NPU/DSP) with automatic multi-threaded ARM CPU fallback.
  - Conceals missing or damaged video frames in latent space to prevent playback stutter during network packet drops.
- **Live Telemetry**:
  - Reports Instant FPS, average FPS, network bitrate (kbps), and neural inference latency (ms).

### 4. Zero-Drop Live Network Streaming Buffering
- **Anti-Stall Hysteresis**:
  - `InfinityLoadControl` maintains a tight 3s min/max buffer hysteresis to prevent live streaming TCP edge servers from timing out and sending premature `input EOS` disconnects.

---

## Installation

### Step 1: Add JitPack to your project

In your root `settings.gradle` or root `build.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2: Add the dependency

In your app-level `build.gradle`:

```groovy
dependencies {
    implementation 'com.github.asheeshsahu7300:infinity-media-player:1.2.0'
}
```

---

## Complete Kotlin Usage Example

### 1. In your Layout XML

```xml
<com.infinity.mediaplayer.ui.InfinityPlayerView
    android:id="@+id/playerView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### 2. In your Activity or Fragment

```kotlin
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.infinity.mediaplayer.audio.AudioOutputMode
import com.infinity.mediaplayer.audio.AudioTelemetry
import com.infinity.mediaplayer.audio.InfinityAudioTrack
import com.infinity.mediaplayer.codec.NvcTelemetry
import com.infinity.mediaplayer.core.InfinityPlayer
import com.infinity.mediaplayer.core.InfinityPlayerConfig
import com.infinity.mediaplayer.core.InfinityPlayerListener
import com.infinity.mediaplayer.subtitle.InfinitySubtitleTrack
import com.infinity.mediaplayer.ui.InfinityPlayerView

class MainActivity : AppCompatActivity() {

    private lateinit var player: InfinityPlayer
    private lateinit var playerView: InfinityPlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)

        // 1. Configure Player options
        val config = InfinityPlayerConfig.Builder()
            .setEnableNvcConcealment(true)
            .setAudioOutputMode(AudioOutputMode.AUTO) // Qualcomm ACDB safety
            .setPreferredAudioLanguages(listOf("hi", "en", "ta"))
            .setPreferredSubtitleLanguages(listOf("en", "hi"))
            .setBufferHysteresis(minMs = 12000L, maxMs = 15000L)
            .build()

        player = InfinityPlayer(this, config)
        playerView.attachPlayer(player)

        // 2. Attach Telemetry and Track Listeners
        player.addListener(object : InfinityPlayerListener {
            override fun onAudioTracksAvailable(
                tracks: List<InfinityAudioTrack>,
                selectedTrack: InfinityAudioTrack?
            ) {
                tracks.forEach { track ->
                    Log.i("AudioTracks", "${track.displayTitle} (Selected: ${track.isSelected})")
                }
            }

            override fun onSubtitleTracksAvailable(
                tracks: List<InfinitySubtitleTrack>,
                selectedTrack: InfinitySubtitleTrack?
            ) {
                tracks.forEach { sub ->
                    Log.i("Subtitles", "${sub.displayTitle} (Selected: ${sub.isSelected})")
                }
            }

            override fun onAudioTelemetryUpdated(telemetry: AudioTelemetry) {
                // telemetry.codec -> "audio/mp4a-latm"
                // telemetry.outputMode -> STEREO_PCM
                // telemetry.underruns -> 0
                // telemetry.acdbErrorCount -> 0
            }

            override fun onNvcTelemetryUpdated(telemetry: NvcTelemetry) {
                // telemetry.instantFps -> 29.8
                // telemetry.isNnapiActive -> true
                // telemetry.avgInferenceLatencyMs -> 3.2ms
            }

            override fun onLiveStreamRecovered() {
                Log.w("Player", "Live stream connection restored silently.")
            }
        })

        // 3. Play stream
        player.play(
            url = "http://example.com/live/stream.ts",
            headers = mapOf("User-Agent" to "InfinityPlayer/1.0"),
            isLive = true
        )
    }

    // Audio Track Switching Example
    fun switchAudio(trackId: String) {
        player.selectAudioTrack(trackId) // Seamless: video continues uninterrupted
    }

    // Subtitle Control Example
    fun switchSubtitle(subtitleId: String) {
        player.selectSubtitleTrack(subtitleId)
    }

    fun turnOffSubtitles() {
        player.disableSubtitles()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
```

---

## Configuration Reference

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `audioOutputMode` | `AudioOutputMode` | `AUTO` | Automatic hardware mitigation for Qualcomm ACDB crashes; or explicit PCM/Passthrough. |
| `preferredAudioLanguages` | `List<String>` | `["hi", "en"]` | Priority list for automatic audio language selection. |
| `preferredSubtitleLanguages` | `List<String>` | `["en", "hi"]` | Priority list for automatic subtitle language selection. |
| `enableNvcConcealment` | `Boolean` | `true` | Enables ONNX Runtime NNAPI/ARM latent frame concealment. |
| `minBufferMs` | `Long` | `12000L` | Minimum buffer threshold before resuming playback data consumption. |
| `maxBufferMs` | `Long` | `15000L` | Maximum forward buffer limit to prevent live stream TCP edge socket timeouts. |
| `bufferForPlaybackMs` | `Long` | `1500L` | Buffer required to start initial rendering. |
| `bufferForPlaybackAfterRebufferMs` | `Long` | `2500L` | Buffer required to resume playback after re-buffering. |
| `lowLatencyMpegTs` | `Boolean` | `true` | Optimizes TsExtractor flags for immediate PTS/DTS sync on live streams. |
| `reconnectTimeoutMs` | `Long` | `15000L` | Maximum duration before initiating silent session recovery. |

---

## License

This library is distributed under the [MIT License](LICENSE).
