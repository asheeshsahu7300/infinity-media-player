package com.infinity.mediaplayer.core

data class InfinityPlayerConfig(
    val minBufferMs: Long = 12000L,
    val maxBufferMs: Long = 15000L,
    val bufferForPlaybackMs: Long = 1500L,
    val bufferForPlaybackAfterRebufferMs: Long = 2500L,
    val enableNvcConcealment: Boolean = true,
    val forceStereoPcmAudio: Boolean = true,
    val preferredAudioLanguages: List<String> = listOf("hin", "eng"),
    val lowLatencyMpegTs: Boolean = true,
    val reconnectTimeoutMs: Long = 15000L
) {
    class Builder {
        private var minBufferMs: Long = 12000L
        private var maxBufferMs: Long = 15000L
        private var bufferForPlaybackMs: Long = 1500L
        private var bufferForPlaybackAfterRebufferMs: Long = 2500L
        private var enableNvcConcealment: Boolean = true
        private var forceStereoPcmAudio: Boolean = true
        private var preferredAudioLanguages: List<String> = listOf("hin", "eng")
        private var lowLatencyMpegTs: Boolean = true
        private var reconnectTimeoutMs: Long = 15000L

        fun setBufferHysteresis(minMs: Long, maxMs: Long) = apply {
            this.minBufferMs = minMs
            this.maxBufferMs = maxMs
        }

        fun setBufferForPlayback(playbackMs: Long, rebufferMs: Long) = apply {
            this.bufferForPlaybackMs = playbackMs
            this.bufferForPlaybackAfterRebufferMs = rebufferMs
        }

        fun setEnableNvcConcealment(enable: Boolean) = apply {
            this.enableNvcConcealment = enable
        }

        fun setForceStereoPcmAudio(force: Boolean) = apply {
            this.forceStereoPcmAudio = force
        }

        fun setPreferredAudioLanguages(langs: List<String>) = apply {
            this.preferredAudioLanguages = langs
        }

        fun setLowLatencyMpegTs(enable: Boolean) = apply {
            this.lowLatencyMpegTs = enable
        }

        fun build() = InfinityPlayerConfig(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
            enableNvcConcealment,
            forceStereoPcmAudio,
            preferredAudioLanguages,
            lowLatencyMpegTs,
            reconnectTimeoutMs
        )
    }
}
