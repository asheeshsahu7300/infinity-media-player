package com.infinity.mediaplayer.core

import com.infinity.mediaplayer.codec.NvcTelemetry

interface InfinityPlayerListener {
    fun onPlaybackStateChanged(isPlaying: Boolean, isBuffering: Boolean) {}
    fun onTelemetryUpdated(telemetry: NvcTelemetry) {}
    fun onLiveStreamRecovered() {}
    fun onTracksChanged(audioTracks: List<String>, selectedAudioTrack: String?) {}
    fun onError(error: Throwable) {}
}
