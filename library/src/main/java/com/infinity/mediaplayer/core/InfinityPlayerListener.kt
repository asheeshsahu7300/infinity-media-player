package com.infinity.mediaplayer.core

import androidx.media3.common.PlaybackException
import com.infinity.mediaplayer.audio.AudioTelemetry
import com.infinity.mediaplayer.audio.InfinityAudioTrack
import com.infinity.mediaplayer.codec.NvcTelemetry
import com.infinity.mediaplayer.subtitle.InfinitySubtitleTrack
import com.infinity.mediaplayer.video.InfinityVideoTrack

interface InfinityPlayerListener {
    fun onPlaybackStateChanged(isPlaying: Boolean, isBuffering: Boolean) {}
    fun onNvcTelemetryUpdated(telemetry: NvcTelemetry) {}
    fun onAudioTelemetryUpdated(telemetry: AudioTelemetry) {}
    fun onLiveStreamRecovered() {}
    fun onVideoTracksAvailable(tracks: List<InfinityVideoTrack>, activeTrack: InfinityVideoTrack?) {}
    fun onAudioTracksAvailable(tracks: List<InfinityAudioTrack>, activeTrack: InfinityAudioTrack?) {}
    fun onSubtitleTracksAvailable(tracks: List<InfinitySubtitleTrack>, activeTrack: InfinitySubtitleTrack?) {}
    fun onRecoveredFromStall(stallDurationMs: Long = 300L) {}
    fun onError(error: PlaybackException) {}
    fun onError(error: Throwable) {}
}
