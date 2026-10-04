# 🚀 Infinity Media Player for Android

[![JitPack](https://jitpack.io/v/asheeshsahu7300/infinity-media-player.svg)](https://jitpack.io/#asheeshsahu7300/infinity-media-player)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-24-brightgreen.svg)](https://developer.android.com)
[![Media3](https://img.shields.io/badge/Media3-1.4.1-orange.svg)](https://developer.android.com/media/media3)
[![ONNX Runtime](https://img.shields.io/badge/ONNX%20Runtime-NNAPI%20%7C%20ARM-blueviolet.svg)](https://onnxruntime.ai/)

An all-in-one, ultra-resilient Android Media Player library combining **Google Media3 (ExoPlayer)** with an embedded **NVC-Live Neural Video Latent Concealer** (ONNX Runtime / NNAPI), designed specifically for seamless live IPTV streaming, zero-stall network hysteresis, and Qualcomm ACDB hardware audio safety.

---

## ✨ Features

- **🧠 NVC-Live Neural Video Latency & Packet Loss Concealment**:
  - Embedded ONNX Runtime engine utilizing Android NNAPI hardware acceleration (NPU/DSP) with automatic ARM multi-threaded CPU fallback.
  - Conceals missing/corrupted video frames in latent space without stalling the render pipeline.
  - Real-time telemetry monitoring: Instant FPS, bitrate (kbps), inference latency (ms), and frame concealment counter.

- **🔊 Qualcomm ADSP & Dirac Hardware Audio Safety**:
  - Resolves Qualcomm ACDB `acdb_loader_adsp_set_audio_cal` errors (`result=-100` on topology `0x10012d00`, apptypes `0x11136`/`0x11130`).
  - Automatically downmixes multichannel audio (AC-3 5.1, E-AC3, AAC 5.1) to clean 16-bit 48kHz stereo PCM before feeding hardware AudioTrack.
  - Prevents audio packet drops, stutter, and device crash on OnePlus, Oppo, Realme, Xiaomi, and Samsung devices.

- **⚡ Zero-Drop Live IPTV Hysteresis Buffering**:
  - Continuous TCP socket consumption tuned with low-hysteresis min/max buffers to prevent edge server write timeouts and premature `input EOS` drops.
  - Silent auto-recovery watchdog for live stream drops.

- **📡 Robust MPEG-TS Extractor**:
  - Configured with `FLAG_ALLOW_NON_IDR_KEYFRAMES`, single PMT extraction, and splice info tolerance for instant stream start.

---

## 📦 Installation

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
    implementation 'com.github.asheeshsahu7300:infinity-media-player:1.0.0'
}
```

---

## 🚀 Quick Start (Kotlin)

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
import androidx.appcompat.app.AppCompatActivity
import com.infinity.mediaplayer.core.InfinityPlayer
import com.infinity.mediaplayer.core.InfinityPlayerConfig
import com.infinity.mediaplayer.core.InfinityPlayerListener
import com.infinity.mediaplayer.codec.NvcTelemetry
import com.infinity.mediaplayer.ui.InfinityPlayerView

class MainActivity : AppCompatActivity() {

    private lateinit var player: InfinityPlayer
    private lateinit var playerView: InfinityPlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)

        // 1. Configure the Player
        val config = InfinityPlayerConfig.Builder()
            .setEnableNvcConcealment(true) // Enable NNAPI neural frame concealment
            .setForceStereoPcmAudio(true)  // Prevent Qualcomm ACDB audio packet drops
            .setBufferHysteresis(minMs = 12000L, maxMs = 15000L)
            .build()

        player = InfinityPlayer(this, config)
        playerView.attachPlayer(player)

        // 2. Listen to real-time telemetry & events
        player.addListener(object : InfinityPlayerListener {
            override fun onTelemetryUpdated(telemetry: NvcTelemetry) {
                // telemetry.instantFps -> 27.5 fps
                // telemetry.bitrateKbps -> 4500 kbps
                // telemetry.isNnapiActive -> true (NNAPI / NPU active)
                // telemetry.concealedFrames -> 3 frames concealed
            }

            override fun onLiveStreamRecovered() {
                // Stream reconnected automatically without crashing
            }
        })

        // 3. Play Live Stream or Video
        player.play(
            url = "http://example.com/live/stream.ts",
            headers = mapOf("User-Agent" to "InfinityPlayer/1.0"),
            isLive = true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
```

---

## 🛠️ Architecture

```
Infinity Media Player
 ├── core/
 │    ├── InfinityPlayer.kt           <- High-level player orchestrator
 │    ├── InfinityPlayerConfig.kt     <- Configuration builder (buffers, audio, codec)
 │    ├── InfinityLoadControl.kt      <- Low-hysteresis anti-stall buffer controller
 │    ├── InfinityMediaSourceFactory.kt <- MPEG-TS & OkHttp live streaming factory
 │    └── InfinityPlayerListener.kt   <- Telemetry & lifecycle callbacks
 ├── codec/
 │    ├── NvcNeuralConcealer.kt       <- ONNX Runtime NNAPI/CPU latent concealer
 │    └── NvcTelemetry.kt             <- Real-time diagnostics model
 └── ui/
      └── InfinityPlayerView.kt       <- Clean Media3 Surface/Texture wrapper
```

---

## 📄 License

This library is distributed under the [MIT License](LICENSE).
