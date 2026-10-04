# Infinity Media Player (Android)

High-performance, resilient Android media player engine powered by AndroidX Media3 (ExoPlayer), featuring Neural Video Latent Concealment (NVC-Live), Qualcomm ACDB audio HAL safety, low-latency live network streaming buffering, seamless video/audio track switching, and presentation-timestamped (PTS) subtitles.

---

## Architectural Highlights

- **NVC-Live Neural Video Latent Concealment**: Neural recovery engine running quantized ONNX Runtime models via Android Neural Networks API (NNAPI) with automatic fallback to multi-threaded ARM CPU execution. In v1.2.1, NVC operates in a synchronized sidecar evaluation mode measuring frame inference latency and neural budget under network dropouts. Full neural frame reconstruction injection into the hardware video surface is in active development for v1.3.0.
- **Hardware-Aware Audio Safety Controller**: Proactively inspects Qualcomm Snapdragon chipsets and Dirac vendor audio frameworks (OnePlus, Oppo, Realme, Xiaomi). Prevents fatal Hexagon ADSP ACDB audio calibration crashes (`result=-100` on topology `0x10012d00`) by configuring standard 16-bit 48 kHz stereo PCM downmixing through a dedicated safe `DefaultAudioSink`.
- **Software FFmpeg Fallback**: Integrates Jellyfin's Media3 FFmpeg decoder extension (`FfmpegAudioRenderer`) for guaranteed software playback of AC-3, E-AC3, and DTS audio streams when hardware codecs or platform licenses are missing.
- **First-Class Seamless Track Switching**: Seamlessly change video resolutions (4K, 1080p, 720p, 480p), audio languages, and subtitle tracks on the fly during active playback without video pipeline re-initialization or network rebuffering.
- **PTS-Synchronized Subtitle Engine**: Subtitle cues (WebVTT, SubRip SRT, TTML, ASS/SSA) stay strictly synchronized to the media presentation timestamp clock, preventing subtitle drift during buffering recovery and network reconnects.
- **Accurate Telemetry Pipeline**: Built on Media3 `AnalyticsListener` capturing real hardware decoder names, actual audio buffer underruns, dropped video frame counts, dynamic bandwidth estimates, source FPS vs. rendered FPS, and millisecond-level playout latencies.
- **Resilient Recovery with Header Persistence**: Stateful live network streaming recovery preserves original HTTP headers (User-Agent, Authorization, Cookies, Tokens) across reconnections and enforces configurable `reconnectTimeoutMs` limits with exponential backoff.

---

## Architecture Diagram

```
Infinity Media Player
│
├── Video Pipeline
│   ├── Media3 ExoPlayer Video Track Selection (4K / 1080p / 720p / 480p)
│   ├── Hardware MediaCodec (AVC/H.264, HEVC/H.265)
│   ├── NVC Neural Latent Concealer (ONNX Runtime / NNAPI sidecar)
│   └── Android Surface Rendering
│
├── Audio Pipeline
│   ├── Audio Track Selection (Languages & Codecs)
│   ├── Audio Safety Controller (Qualcomm / Dirac Detection)
│   ├── Configurable AudioOutputMode (AUTO / STEREO_PCM / MULTICHANNEL / PASSTHROUGH)
│   ├── Media3 Safe AudioSink (16-bit 48 kHz PCM downmix)
│   ├── FFmpeg Software Audio Decoder Fallback (AC3, E-AC3, DTS)
│   └── Device AudioTrack & Hardware HAL
│
├── Subtitle Subsystem
│   ├── Embedded & Sidecar Subtitle Extractor (WebVTT, SRT, TTML, ASS/SSA)
│   ├── PTS Timeline Synchronization Clock
│   └── InfinityPlayerView Subtitle Rendering Layer
│
└── Telemetry & Diagnostics
    ├── AnalyticsListener Hardware Measurements
    ├── Audio Underrun & ACDB Anomaly Tracking
    ├── Source FPS vs. Measured Rendered FPS
    ├── Real Decoder Names (c2.qti.*) & Audio Playout Latency
    └── Stateful Network Recovery with Persistent Headers
```

---

## Installation

### 1. Add Maven Repository
Add JitPack to your root `settings.gradle` or root `build.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### 2. Add Dependency
Add the library to your module `build.gradle`:

```groovy
dependencies {
    implementation 'com.github.asheeshsahu7300:infinity-media-player:v1.2.1'
}
```

---

## Usage Guide

### 1. Initialize Player with Configuration

```kotlin
val config = InfinityPlayerConfig.Builder()
    .setPreferredAudioLanguages(listOf("hi", "en"))
    .setPreferredSubtitleLanguages(listOf("en", "hi"))
    .setAudioOutputMode(AudioOutputMode.AUTO)
    .setBufferHysteresis(minMs = 12000L, maxMs = 15000L)
    .setBufferForPlayback(playbackMs = 1500L, rebufferMs = 2500L)
    .setReconnectTimeoutMs(15000L)
    .setEnableNvcConcealment(true)
    .setLowLatencyMpegTs(true)
    .build()

val player = InfinityPlayer(context, config)
```

### 2. Bind View and Playback

```kotlin
val playerView = findViewById<InfinityPlayerView>(R.id.player_view)
playerView.attachPlayer(player)

player.play(
    url = "https://example.com/live/stream.ts",
    headers = mapOf(
        "User-Agent" to "InfinityMediaPlayer/1.2",
        "Authorization" to "Bearer <token>"
    ),
    isLive = true
)
```

### 3. Video Track Discovery & Resolution Switching

```kotlin
// Retrieve available video tracks
val videoTracks: List<InfinityVideoTrack> = player.getVideoTracks()
videoTracks.forEach { track ->
    Log.d("Video", "ID: ${track.id}, Resolution: ${track.displayTitle} (${track.width}x${track.height}), Bitrate: ${track.bitrate}")
}

// Seamlessly switch to 720p or 1080p without restarting playback
player.selectVideoTrack(targetTrackId)

// Restore adaptive bitrate switching
player.setAutoVideoTrack()
```

### 4. Audio Track Discovery & Switching

```kotlin
val audioTracks: List<InfinityAudioTrack> = player.getAudioTracks()
audioTracks.forEach { track ->
    Log.d("Audio", "ID: ${track.id}, Language: ${track.language}, Title: ${track.displayTitle}, Supported: ${track.isSupported}")
}

// Switch audio track seamlessly
player.selectAudioTrack(selectedTrackId)
```

### 5. Subtitle Management

```kotlin
val subtitleTracks: List<InfinitySubtitleTrack> = player.getSubtitleTracks()
subtitleTracks.forEach { track ->
    Log.d("Subtitle", "ID: ${track.id}, Language: ${track.language}, Title: ${track.displayTitle}")
}

// Select a specific subtitle track
player.selectSubtitleTrack(selectedSubtitleTrackId)

// Disable subtitles
player.disableSubtitles()
```

### 6. Accurate Telemetry Listener

```kotlin
player.addListener(object : InfinityPlayerListener {
    override fun onNvcTelemetryUpdated(telemetry: NvcTelemetry) {
        Log.d("NVC", "Provider: ${telemetry.executionProvider}, Source FPS: ${telemetry.sourceFps}, Rendered FPS: ${telemetry.renderedFps}, Concealed: ${telemetry.concealedFrames}")
    }

    override fun onAudioTelemetryUpdated(telemetry: AudioTelemetry) {
        Log.d("Audio", "Decoder: ${telemetry.decoderName}, Underruns: ${telemetry.underruns}, Latency: ${telemetry.audioLatencyMs}ms, ACDB Errors: ${telemetry.acdbErrorCount}")
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, isBuffering: Boolean) {
        Log.d("Player", "isPlaying: $isPlaying, isBuffering: $isBuffering")
    }

    override fun onRecoveredFromStall(stallDurationMs: Long) {
        Log.w("Player", "Stream auto-recovered after ${stallDurationMs}ms stall")
    }
})
```

---

## Hardware Compatibility & Validation Matrix

Tested on physical production hardware across diverse SoC architectures:

| Device | SoC Architecture | OS Version | Hardware Video Decoder | Audio Safety Route | NVC Provider |
|---|---|---|---|---|---|
| OnePlus Nord CE (EB2101) | Qualcomm Snapdragon 750G (SM7225) | Android 13 | c2.qti.avc.decoder | Safe Stereo PCM (ACDB Protected) | NNAPI |
| POCO F3 / Xiaomi Mi 11X | Qualcomm Snapdragon 870 (SM8250-AC) | Android 13 | c2.qti.avc.decoder | Safe Stereo PCM (ACDB Protected) | NNAPI |
| Google Pixel 7 | Google Tensor G2 | Android 14 | c2.exynos.h264.decoder | Multichannel PCM | NNAPI |
| Samsung Galaxy S21 | Exynos 2100 | Android 13 | c2.exynos.h264.decoder | Multichannel PCM | ARM-CPU Fallback |

---

## Supported Formats & Protocols

- **Streaming Protocols**: MPEG-TS over HTTP/HTTPS, HLS (RFC 8216), DASH (ISO/IEC 23009-1), Progressive MP4/MKV.
- **Video Codecs**: AVC / H.264, HEVC / H.265, VP9.
- **Audio Codecs**: AAC-LC, HE-AAC v1/v2, AC-3 (Dolby Digital), E-AC3 (Dolby Digital Plus), DTS, MP3.
- **Subtitle Formats**: WebVTT, SubRip (SRT), TTML, ASS/SSA (embedded or sidecar).

---

## Changelog

### v1.2.1
- Fixed request header persistence during live network stream recovery (User-Agent, Authorization tokens, and Cookies preserved).
- Implemented functional `reconnectTimeoutMs` recovery loop with progressive backoff to prevent reconnect loops.
- Separated source stream FPS and actual measured rendered FPS in NVC telemetry.
- Removed synthetic audio latency; real playout latency measured via `onAudioPositionAdvancing`.
- Integrated FFmpeg audio software decoder extension (`media3-ffmpeg-decoder`).

### v1.2.0
- Added hardware-level telemetry powered by Media3 `AnalyticsListener`.
- Introduced first-class `InfinityVideoTrack` model and seamless resolution switching (`getVideoTracks()`, `selectVideoTrack()`, `setAutoVideoTrack()`).
- Added automated JUnit test coverage for audio output modes, track metadata, and buffer hysteresis configuration.
- Enhanced Qualcomm detection heuristics for Snapdragon 7xx, 8xx, and Gen series SoCs.

### v1.1.0
- Added first-class `InfinityAudioTrack` discovery and seamless non-restarting audio track switching.
- Introduced `AudioSafetyController` with configurable `AudioOutputMode` (`AUTO`, `STEREO_PCM`, `MULTICHANNEL_PCM`, `PASSTHROUGH`).
- Implemented PTS-synchronized subtitle engine supporting WebVTT, SRT, TTML, and ASS/SSA.
- Added language preference prioritization for audio and subtitles.

### v1.0.0
- Initial standalone release with NVC Neural Video Concealment (ONNX Runtime + NNAPI).
- Custom `InfinityLoadControl` anti-stall live buffering engine.
- Media3 MPEG-TS live network streaming extractor.

---

## License

MIT License. Copyright (c) 2026 Asheesh Sahu.
