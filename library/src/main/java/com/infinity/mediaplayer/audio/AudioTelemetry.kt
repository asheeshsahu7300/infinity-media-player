package com.infinity.mediaplayer.audio

/**
 * Real-time audio pipeline metrics and hardware safety status.
 */
data class AudioTelemetry(
    val codec: String?,
    val sampleRate: Int,
    val channels: Int,
    val outputMode: AudioOutputMode,
    val decoderName: String?,
    val underruns: Int,
    val droppedAudioFrames: Long? = 0L,
    val acdbErrorCount: Int,
    val audioLatencyMs: Long?,
    val bitrateEstimate: Long = 0L,
    val isSafetyLayerActive: Boolean = true
) {
    val channelConfigurationLabel: String
        get() = when (channels) {
            1 -> "Mono (1.0)"
            2 -> "Stereo (2.0)"
            6 -> "Surround (5.1 downmixed to Stereo)"
            8 -> "Surround (7.1 downmixed to Stereo)"
            else -> "$channels Channels"
        }
}
