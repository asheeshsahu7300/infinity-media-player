package com.infinity.mediaplayer.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.infinity.mediaplayer.audio.AudioSafetyController
import com.infinity.mediaplayer.audio.AudioTelemetry
import com.infinity.mediaplayer.audio.InfinityAudioTrack
import com.infinity.mediaplayer.codec.NvcNeuralConcealer
import com.infinity.mediaplayer.codec.NvcTelemetry
import com.infinity.mediaplayer.subtitle.InfinitySubtitleTrack
import com.infinity.mediaplayer.video.InfinityVideoTrack

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

    // Subsystems
    val neuralConcealer: NvcNeuralConcealer? = if (config.enableNvcConcealment) {
        NvcNeuralConcealer(context)
    } else null

    val audioSafetyController = AudioSafetyController(context)

    // Track internal mappings: ID -> Pair(TrackGroup, Index)
    private val videoFormatMap = mutableMapOf<String, Pair<TrackGroup, Int>>()
    private val audioFormatMap = mutableMapOf<String, Pair<TrackGroup, Int>>()
    private val subtitleFormatMap = mutableMapOf<String, Pair<TrackGroup, Int>>()

    private val currentVideoTracks = mutableListOf<InfinityVideoTrack>()
    private val currentAudioTracks = mutableListOf<InfinityAudioTrack>()
    private val currentSubtitleTracks = mutableListOf<InfinitySubtitleTrack>()

    private var activeVideoTrack: InfinityVideoTrack? = null
    private var activeAudioTrack: InfinityAudioTrack? = null
    private var activeSubtitleTrack: InfinitySubtitleTrack? = null

    // Real Hardware & Pipeline Measurements (via AnalyticsListener)
    private var measuredAudioDecoderName: String? = null
    private var measuredVideoDecoderName: String? = null
    private var measuredAudioUnderrunCount: Int = 0
    private var measuredDroppedFrames: Long = 0L
    private var measuredEstimatedBitrate: Long = 0L
    private var lastAudioPlayoutTimestampMs: Long = 0L
    private var measuredAudioLatencyMs: Long? = null

    // Track Selector with safe defaults & 4K support
    val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_OPUS)
            .setPreferredAudioLanguages(*config.preferredAudioLanguages.toTypedArray())
            .setPreferredTextLanguages(*config.preferredSubtitleLanguages.toTypedArray())
            .setConstrainAudioChannelCountToDeviceCapabilities(true)
            .setExceedRendererCapabilitiesIfNecessary(true)
            .setExceedVideoConstraintsIfNecessary(true)
            .build()
    }

    // Custom RenderersFactory with Hardware MediaCodec, Safe PCM AudioSink, and Software FFmpeg Fallback
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
                maxOf(allowedVideoJoiningTimeMs, 10000L),
                out
            )
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParameters: Boolean
        ): AudioSink? {
            return audioSafetyController.buildAudioSink(config.audioOutputMode)
        }

        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: java.util.ArrayList<Renderer>
        ) {
            try {
                out.add(androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
                Log.i(TAG, "Explicitly loaded FfmpegAudioRenderer for software AC3/EAC3/DTS decoding.")
            } catch (t: Throwable) {
                Log.w(TAG, "FFmpeg audio extension could not be loaded: ${t.message}")
            }

            super.buildAudioRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                audioSink,
                eventHandler,
                eventListener,
                out
            )
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
    private var currentHeaders: Map<String, String> = emptyMap()
    private var isLiveStream: Boolean = false
    private var telemetryRunnable: Runnable? = null

    // Stateful Recovery Management
    private var recoveryJob: Runnable? = null
    private var recoveryStartTimeMs: Long = 0L
    private var recoveryAttempt: Int = 0
    private var isRecovering: Boolean = false

    // Active Audio Format Cache
    private var activeAudioCodec: String? = null
    private var activeAudioSampleRate: Int = 0
    private var activeAudioChannels: Int = 0

    init {
        setupPlayerListeners()
        startTelemetryLoop()
    }

    private fun setupPlayerListeners() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val isPlaying = exoPlayer.isPlaying
                val isBuffering = playbackState == Player.STATE_BUFFERING
                listeners.forEach { it.onPlaybackStateChanged(isPlaying, isBuffering) }

                if (playbackState == Player.STATE_READY && isPlaying) {
                    if (isRecovering) {
                        Log.i(TAG, "Live network stream recovered successfully after attempt #$recoveryAttempt.")
                        cancelRecovery()
                    }
                }

                if (playbackState == Player.STATE_ENDED && isLiveStream) {
                    Log.w(TAG, "Live network stream ended (input EOS). Triggering recovery.")
                    triggerRecovery("STATE_ENDED")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listeners.forEach { it.onPlaybackStateChanged(isPlaying, exoPlayer.playbackState == Player.STATE_BUFFERING) }
                if (isPlaying && isRecovering) {
                    cancelRecovery()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                processTrackChanges(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                if (error.errorCodeName.contains("AUDIO", ignoreCase = true)) {
                    audioSafetyController.recordAcdbError()
                }
                listeners.forEach {
                    it.onError(error)
                    it.onError(error as Throwable)
                }

                if (isLiveStream) {
                    triggerRecovery("ERROR_${error.errorCodeName}")
                }
            }
        })

        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                measuredAudioDecoderName = decoderName
                Log.d(TAG, "Hardware Audio Decoder Initialized: $decoderName in ${initializationDurationMs}ms")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                measuredVideoDecoderName = decoderName
                Log.d(TAG, "Hardware Video Decoder Initialized: $decoderName in ${initializationDurationMs}ms")
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                measuredAudioUnderrunCount++
                audioSafetyController.recordUnderrun()
                Log.w(TAG, "Audio underrun detected (bufferSizeMs=$bufferSizeMs, total=$measuredAudioUnderrunCount)")
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                audioSafetyController.recordAcdbError()
                Log.e(TAG, "AudioSink error recorded: ${audioSinkError.message}", audioSinkError)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                measuredDroppedFrames += droppedFrames
                if (droppedFrames > 0) {
                    neuralConcealer?.recordDroppedFrame()
                }
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int
            ) {
                if (frameCount > 0) {
                    neuralConcealer?.recordRenderedFrames(frameCount)
                }
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                neuralConcealer?.recordRenderedFrames(1)
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                measuredEstimatedBitrate = bitrateEstimate
            }

            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long
            ) {
                lastAudioPlayoutTimestampMs = playoutStartSystemTimeMs
                val now = SystemClock.elapsedRealtime()
                if (playoutStartSystemTimeMs > 0 && now >= playoutStartSystemTimeMs) {
                    measuredAudioLatencyMs = (now - playoutStartSystemTimeMs).coerceAtLeast(0L)
                }
            }
        })
    }

    private fun processTrackChanges(tracks: Tracks) {
        videoFormatMap.clear()
        audioFormatMap.clear()
        subtitleFormatMap.clear()

        currentVideoTracks.clear()
        currentAudioTracks.clear()
        currentSubtitleTracks.clear()

        var selectedVideo: InfinityVideoTrack? = null
        var selectedAudio: InfinityAudioTrack? = null
        var selectedSubtitle: InfinitySubtitleTrack? = null

        for (group in tracks.groups) {
            val mediaTrackGroup = group.mediaTrackGroup
            val trackType = group.type

            for (i in 0 until group.length) {
                val format: Format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)

                when (trackType) {
                    C.TRACK_TYPE_VIDEO -> {
                        val videoId = format.id ?: "${format.width}x${format.height}_${format.bitrate}_$i"
                        videoFormatMap[videoId] = mediaTrackGroup to i

                        val videoTrack = InfinityVideoTrack(
                            id = videoId,
                            width = if (format.width != Format.NO_VALUE) format.width else 0,
                            height = if (format.height != Format.NO_VALUE) format.height else 0,
                            frameRate = if (format.frameRate != Format.NO_VALUE.toFloat()) format.frameRate else 0f,
                            bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else null,
                            codec = format.codecs ?: format.sampleMimeType,
                            isSelected = isSelected
                        )
                        currentVideoTracks.add(videoTrack)
                        if (isSelected) {
                            selectedVideo = videoTrack
                        }
                    }

                    C.TRACK_TYPE_AUDIO -> {
                        val trackId = format.id ?: "${format.sampleMimeType}_${format.channelCount}_${format.sampleRate}_$i"
                        audioFormatMap[trackId] = mediaTrackGroup to i

                        val isTrackSupported = group.isTrackSupported(i)
                        val audioTrack = InfinityAudioTrack(
                            id = trackId,
                            language = format.language,
                            label = format.label,
                            mimeType = format.sampleMimeType,
                            codec = format.codecs,
                            channelCount = format.channelCount,
                            sampleRate = format.sampleRate,
                            bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else null,
                            isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                            isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                            isSelected = isSelected,
                            isSupported = isTrackSupported
                        )
                        currentAudioTracks.add(audioTrack)
                        if (isSelected) {
                            selectedAudio = audioTrack
                            activeAudioCodec = format.sampleMimeType
                            activeAudioSampleRate = format.sampleRate
                            activeAudioChannels = format.channelCount
                        }
                    }

                    C.TRACK_TYPE_TEXT -> {
                        val subId = format.id ?: "subtitle_${format.language}_$i"
                        subtitleFormatMap[subId] = mediaTrackGroup to i

                        val subTrack = InfinitySubtitleTrack(
                            id = subId,
                            language = format.language,
                            label = format.label,
                            mimeType = format.sampleMimeType,
                            isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                            isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                            isClosedCaption = (format.roleFlags and C.ROLE_FLAG_CAPTION) != 0,
                            isSelected = isSelected
                        )
                        currentSubtitleTracks.add(subTrack)
                        if (isSelected) {
                            selectedSubtitle = subTrack
                        }
                    }
                }
            }
        }

        activeVideoTrack = selectedVideo
        activeAudioTrack = selectedAudio
        activeSubtitleTrack = selectedSubtitle

        listeners.forEach {
            it.onVideoTracksAvailable(currentVideoTracks.toList(), activeVideoTrack)
            it.onAudioTracksAvailable(currentAudioTracks.toList(), activeAudioTrack)
            it.onSubtitleTracksAvailable(currentSubtitleTracks.toList(), activeSubtitleTrack)
        }
    }

    // Video Track APIs

    fun getVideoTracks(): List<InfinityVideoTrack> = currentVideoTracks.toList()

    fun getSelectedVideoTrack(): InfinityVideoTrack? = activeVideoTrack

    fun selectVideoTrack(trackId: String): Boolean {
        if (trackId == "auto" || trackId.isEmpty() || trackId == "-1") {
            setAutoVideoTrack()
            return true
        }
        val target = videoFormatMap[trackId] ?: return false
        val newParams = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .addOverride(TrackSelectionOverride(target.first, target.second))
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        exoPlayer.trackSelectionParameters = newParams
        Log.i(TAG, "Selected video track: $trackId")
        return true
    }

    fun setAutoVideoTrack() {
        val newParams = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        exoPlayer.trackSelectionParameters = newParams
        Log.i(TAG, "Restored adaptive video track selection.")
    }

    // Audio Track APIs

    fun getAudioTracks(): List<InfinityAudioTrack> = currentAudioTracks.toList()

    fun getSelectedAudioTrack(): InfinityAudioTrack? = activeAudioTrack

    fun selectAudioTrack(trackId: String): Boolean {
        if (trackId == "-1" || trackId == "disabled") {
            val newParams = exoPlayer.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            exoPlayer.trackSelectionParameters = newParams
            activeAudioTrack = null
            Log.i(TAG, "Audio track disabled")
            if (exoPlayer.playerError != null) {
                exoPlayer.prepare()
            }
            return true
        }
        val target = audioFormatMap[trackId] ?: return false
        val newParams = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(target.first, target.second))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        exoPlayer.trackSelectionParameters = newParams
        Log.i(TAG, "Switched to audio track: $trackId")
        return true
    }

    // Subtitle APIs

    fun getSubtitleTracks(): List<InfinitySubtitleTrack> = currentSubtitleTracks.toList()

    fun getSelectedSubtitleTrack(): InfinitySubtitleTrack? = activeSubtitleTrack

    fun selectSubtitleTrack(trackId: String): Boolean {
        val target = subtitleFormatMap[trackId] ?: return false
        val newParams = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(target.first, target.second))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        exoPlayer.trackSelectionParameters = newParams
        Log.i(TAG, "Selected subtitle track: $trackId")
        return true
    }

    fun disableSubtitles() {
        val newParams = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        exoPlayer.trackSelectionParameters = newParams
        activeSubtitleTrack = null
        Log.i(TAG, "Subtitles disabled.")
        if (exoPlayer.playerError != null) {
            exoPlayer.prepare()
        }
    }

    // Playback Rate & Volume Controls

    fun setPlaybackRate(rate: Float) {
        try {
            exoPlayer.playbackParameters = PlaybackParameters(rate)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting playback rate", e)
        }
    }

    fun setVolume(volume: Float) {
        try {
            exoPlayer.volume = volume.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting volume", e)
        }
    }

    val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    // Accurate Telemetry Loop

    private fun startTelemetryLoop() {
        telemetryRunnable = object : Runnable {
            override fun run() {
                // NVC Telemetry with real source and rendered FPS
                val sourceFps = exoPlayer.videoFormat?.frameRate ?: 0.0f
                neuralConcealer?.let { concealer ->
                    val telemetry = concealer.getTelemetry(
                        sourceFps = sourceFps,
                        droppedFrames = measuredDroppedFrames,
                        currentBitrateKbps = (measuredEstimatedBitrate / 1000).toInt()
                    )
                    listeners.forEach { it.onNvcTelemetryUpdated(telemetry) }
                }

                // Accurate Audio Telemetry from AnalyticsListener measurements
                val effectiveMode = audioSafetyController.resolveEffectiveMode(config.audioOutputMode)
                val resolvedDecoder = measuredAudioDecoderName ?: if (effectiveMode == com.infinity.mediaplayer.audio.AudioOutputMode.STEREO_PCM) {
                    "MediaCodec (Stereo PCM Safe)"
                } else {
                    activeAudioCodec?.let { "MediaCodec ($it)" } ?: "DefaultMediaCodec"
                }

                val latencyOrNull = if (lastAudioPlayoutTimestampMs > 0L) measuredAudioLatencyMs else null

                val audioTelem = AudioTelemetry(
                    codec = activeAudioCodec,
                    sampleRate = activeAudioSampleRate,
                    channels = activeAudioChannels,
                    outputMode = effectiveMode,
                    decoderName = resolvedDecoder,
                    underruns = measuredAudioUnderrunCount,
                    droppedAudioFrames = 0L,
                    acdbErrorCount = audioSafetyController.acdbErrorCount,
                    audioLatencyMs = latencyOrNull,
                    bitrateEstimate = measuredEstimatedBitrate,
                    isSafetyLayerActive = audioSafetyController.isSafetyActive
                )
                listeners.forEach { it.onAudioTelemetryUpdated(audioTelem) }

                mainHandler.postDelayed(this, 350)
            }
        }
        mainHandler.post(telemetryRunnable!!)
    }

    // Playback Controls

    fun play(url: String, headers: Map<String, String> = emptyMap(), isLive: Boolean = true) {
        this.currentUrl = url
        this.currentHeaders = headers
        this.isLiveStream = isLive
        cancelRecovery()

        val mediaSourceFactory = InfinityMediaSourceFactory.create(context, headers, config)
        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        cancelRecovery()
        exoPlayer.pause()
    }

    fun resume() {
        exoPlayer.play()
    }

    fun stop() {
        cancelRecovery()
        exoPlayer.stop()
    }

    fun release() {
        cancelRecovery()
        telemetryRunnable?.let { mainHandler.removeCallbacks(it) }
        exoPlayer.release()
        neuralConcealer?.release()
        listeners.clear()
        Log.i(TAG, "InfinityPlayer released.")
    }

    fun addListener(listener: InfinityPlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: InfinityPlayerListener) {
        listeners.remove(listener)
    }

    private fun cancelRecovery() {
        recoveryJob?.let { mainHandler.removeCallbacks(it) }
        recoveryJob = null
        isRecovering = false
        recoveryAttempt = 0
        recoveryStartTimeMs = 0L
    }

    private fun triggerRecovery(reason: String) {
        if (!isLiveStream) return
        val url = currentUrl ?: return

        val now = SystemClock.elapsedRealtime()
        if (!isRecovering) {
            isRecovering = true
            recoveryStartTimeMs = now
            recoveryAttempt = 0
            Log.i(TAG, "Initiating live network stream recovery for $url (reason: $reason, timeout: ${config.reconnectTimeoutMs}ms)")
        } else {
            val elapsed = now - recoveryStartTimeMs
            if (elapsed > config.reconnectTimeoutMs) {
                Log.e(TAG, "Live network stream recovery exceeded timeout limit (${elapsed}ms > ${config.reconnectTimeoutMs}ms). Aborting retries.")
                cancelRecovery()
                listeners.forEach {
                    it.onError(PlaybackException("Live stream reconnection timed out after ${elapsed}ms", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
                }
                return
            }
        }

        recoveryAttempt++
        val backoffDelayMs = (recoveryAttempt * 400L).coerceAtMost(2500L)
        Log.w(TAG, "Scheduling recovery attempt #$recoveryAttempt in ${backoffDelayMs}ms...")

        recoveryJob?.let { mainHandler.removeCallbacks(it) }
        recoveryJob = Runnable {
            Log.i(TAG, "Executing recovery attempt #$recoveryAttempt for: $url with ${currentHeaders.size} persistent headers")
            val mediaSourceFactory = InfinityMediaSourceFactory.create(context, currentHeaders, config)
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            listeners.forEach {
                it.onLiveStreamRecovered()
                it.onRecoveredFromStall(backoffDelayMs)
            }
        }
        mainHandler.postDelayed(recoveryJob!!, backoffDelayMs)
    }
}
