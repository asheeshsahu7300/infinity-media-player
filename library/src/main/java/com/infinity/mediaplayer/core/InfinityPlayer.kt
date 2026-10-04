package com.infinity.mediaplayer.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.infinity.mediaplayer.codec.NvcNeuralConcealer
import com.infinity.mediaplayer.codec.NvcTelemetry

@OptIn(UnstableApi::class)
class InfinityPlayer(
    val context: Context,
    val config: InfinityPlayerConfig = InfinityPlayerConfig()
) {
    companion object {
        private const val TAG = "InfinityPlayer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<InfinityPlayerListener>()

    // NVC Neural Codec Engine
    val neuralConcealer: NvcNeuralConcealer? = if (config.enableNvcConcealment) {
        NvcNeuralConcealer(context)
    } else null

    // Track Selector with Qualcomm ACDB safe parameters
    val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG)
            .setPreferredAudioLanguages(*config.preferredAudioLanguages.toTypedArray())
            .setConstrainAudioChannelCountToDeviceCapabilities(true)
            .setExceedRendererCapabilitiesIfNecessary(false)
            .setExceedVideoConstraintsIfNecessary(true)
            .build()
    }

    // Custom RenderersFactory with Hardware MediaCodec and Safe PCM AudioSink
    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: java.util.ArrayList<Renderer>
        ) {
            super.buildVideoRenderers(
                context,
                EXTENSION_RENDERER_MODE_OFF,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out
            )
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParameters: Boolean
        ): AudioSink? {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .apply {
                    if (config.forceStereoPcmAudio) {
                        setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    }
                }
                .build()
                .apply {
                    setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                }
        }
    }
        .forceDisableMediaCodecAsynchronousQueueing()
        .setEnableDecoderFallback(true)

    // LoadControl with anti-stall hysteresis
    val loadControl = InfinityLoadControl.create(config)

    // Underlying ExoPlayer instance
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setLooper(context.mainLooper)
        .build()

    private var currentUrl: String? = null
    private var isLiveStream: Boolean = false
    private var telemetryRunnable: Runnable? = null

    init {
        setupPlayerListener()
        startTelemetryLoop()
    }

    private fun setupPlayerListener() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val isPlaying = exoPlayer.isPlaying
                val isBuffering = playbackState == Player.STATE_BUFFERING
                listeners.forEach { it.onPlaybackStateChanged(isPlaying, isBuffering) }

                if (playbackState == Player.STATE_ENDED && isLiveStream) {
                    Log.w(TAG, "Live stream ended unexpectedly (input EOS). Triggering recovery.")
                    recoverLiveStream()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listeners.forEach { it.onPlaybackStateChanged(isPlaying, exoPlayer.playbackState == Player.STATE_BUFFERING) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audioTracksList = mutableListOf<String>()
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val label = format.language ?: format.sampleMimeType ?: "Audio Track $i"
                            audioTracksList.add(label)
                        }
                    }
                }
                listeners.forEach { it.onTracksChanged(audioTracksList, null) }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                listeners.forEach { it.onError(error) }
            }
        })
    }

    private fun startTelemetryLoop() {
        telemetryRunnable = object : Runnable {
            override fun run() {
                neuralConcealer?.let { concealer ->
                    concealer.recordRenderedFrame()
                    val telemetry = concealer.getTelemetry()
                    listeners.forEach { it.onTelemetryUpdated(telemetry) }
                }
                mainHandler.postDelayed(this, 350)
            }
        }
        mainHandler.post(telemetryRunnable!!)
    }

    fun play(url: String, headers: Map<String, String> = emptyMap(), isLive: Boolean = true) {
        this.currentUrl = url
        this.isLiveStream = isLive

        val mediaSourceFactory = InfinityMediaSourceFactory.create(context, headers, config)
        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        exoPlayer.play()
    }

    fun stop() {
        exoPlayer.stop()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun recoverLiveStream() {
        val url = currentUrl ?: return
        mainHandler.post {
            InfinityMediaSourceFactory.evictConnectionPool()
            play(url, isLive = true)
            listeners.forEach { it.onLiveStreamRecovered() }
        }
    }

    fun addListener(listener: InfinityPlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: InfinityPlayerListener) {
        listeners.remove(listener)
    }

    fun getTelemetry(): NvcTelemetry {
        return neuralConcealer?.getTelemetry() ?: NvcTelemetry()
    }

    fun release() {
        telemetryRunnable?.let { mainHandler.removeCallbacks(it) }
        neuralConcealer?.release()
        exoPlayer.release()
        InfinityMediaSourceFactory.evictConnectionPool()
    }
}
