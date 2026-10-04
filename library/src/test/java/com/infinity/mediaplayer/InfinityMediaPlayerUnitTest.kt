package com.infinity.mediaplayer

import com.infinity.mediaplayer.audio.AudioOutputMode
import com.infinity.mediaplayer.audio.InfinityAudioTrack
import com.infinity.mediaplayer.core.InfinityPlayerConfig
import com.infinity.mediaplayer.video.InfinityVideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InfinityMediaPlayerUnitTest {

    @Test
    fun testAudioOutputModeResolutions() {
        val autoMode = AudioOutputMode.AUTO
        val stereoMode = AudioOutputMode.STEREO_PCM
        val passMode = AudioOutputMode.PASSTHROUGH
        val multiMode = AudioOutputMode.MULTICHANNEL_PCM

        assertEquals("AUTO", autoMode.name)
        assertEquals("STEREO_PCM", stereoMode.name)
        assertEquals("PASSTHROUGH", passMode.name)
        assertEquals("MULTICHANNEL_PCM", multiMode.name)
    }

    @Test
    fun testInfinityAudioTrackProperties() {
        val track = InfinityAudioTrack(
            id = "audio_hi_1",
            language = "hi",
            label = "Hindi Main",
            mimeType = "audio/mp4a-latm",
            codec = "mp4a.40.2",
            channelCount = 6,
            sampleRate = 48000,
            bitrate = 192000,
            isDefault = true,
            isForced = false,
            isSelected = true
        )

        assertEquals("Hindi Main", track.displayTitle)
        assertTrue(track.isMultichannel)
        assertTrue(track.isSelected)
        assertTrue(track.isDefault)
        assertFalse(track.isForced)
    }

    @Test
    fun testInfinityVideoTrackResolutionLabels() {
        val track4k = InfinityVideoTrack("v1", 3840, 2160, 60.0f, 15000000, "hvc1", false)
        val track1080p = InfinityVideoTrack("v2", 1920, 1080, 59.94f, 6000000, "avc1", true)
        val track720p = InfinityVideoTrack("v3", 1280, 720, 30.0f, 3000000, "avc1", false)
        val track480p = InfinityVideoTrack("v4", 854, 480, 29.97f, 1200000, "avc1", false)

        assertEquals("4K (3840x2160)", track4k.resolutionLabel)
        assertEquals("1080p (1920x1080)", track1080p.resolutionLabel)
        assertEquals("720p (1280x720)", track720p.resolutionLabel)
        assertEquals("480p (854x480)", track480p.resolutionLabel)
    }

    @Test
    fun testInfinityPlayerConfigDefaults() {
        val config = InfinityPlayerConfig.Builder()
            .setPreferredAudioLanguages(listOf("hi", "en"))
            .setPreferredSubtitleLanguages(listOf("en"))
            .setAudioOutputMode(AudioOutputMode.STEREO_PCM)
            .setBufferHysteresis(1500L, 5000L)
            .build()

        assertEquals(2, config.preferredAudioLanguages.size)
        assertEquals("hi", config.preferredAudioLanguages[0])
        assertEquals("en", config.preferredAudioLanguages[1])
        assertEquals(AudioOutputMode.STEREO_PCM, config.audioOutputMode)
        assertEquals(1500L, config.minBufferMs)
        assertEquals(5000L, config.maxBufferMs)
        assertTrue(config.enableNvcConcealment)
    }
}
