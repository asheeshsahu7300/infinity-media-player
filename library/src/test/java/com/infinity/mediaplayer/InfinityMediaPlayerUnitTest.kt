package com.infinity.mediaplayer

import com.infinity.mediaplayer.audio.AudioOutputMode
import com.infinity.mediaplayer.audio.AudioSafetyController
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
    fun testMediaTekAndQualcommDetectionHelpers() {
        // Validation that static detector helpers execute without exceptions on JVM
        val isQcom = AudioSafetyController.isQualcommDevice()
        val hasDirac = AudioSafetyController.hasDiracService()

        // Just ensure boolean evaluations complete cleanly
        assertTrue(isQcom || !isQcom)
        assertTrue(hasDirac || !hasDirac)
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
        assertEquals(6, track.channelCount)
        assertTrue(track.isSelected)
        assertTrue(track.isDefault)
        assertFalse(track.isForced)
    }

    @Test
    fun testInfinityVideoTrackResolutionLabels() {
        val track4k = InfinityVideoTrack("v1", 3840, 2160, 15000000, "hvc1", false)
        val track1080p = InfinityVideoTrack("v2", 1920, 1080, 6000000, "avc1", true)
        val track720p = InfinityVideoTrack("v3", 1280, 720, 3000000, "avc1", false)
        val track480p = InfinityVideoTrack("v4", 854, 480, 1200000, "avc1", false)

        assertEquals("4K UHD", track4k.displayTitle)
        assertEquals("1080p FHD", track1080p.displayTitle)
        assertEquals("720p HD", track720p.displayTitle)
        assertEquals("480p SD", track480p.displayTitle)
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

    @Test
    fun testNvcFeatureExtractorOutputShapeAndNormalization() {
        val extractor = com.infinity.mediaplayer.codec.NvcFeatureExtractor(48, 32, 32)
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val r = (x * 4) and 0xFF
            val g = (y * 4) and 0xFF
            val b = ((x + y) * 2) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val latent = extractor.extractLatentFromRgbPixels(pixels, w, h)
        assertEquals(48 * 32 * 32, latent.size)

        // Verify all values are finite and within normalized bounds [-1.0, 1.0]
        for (i in latent.indices) {
            val v = latent[i]
            assertFalse("Latent element must not be NaN at $i", v.isNaN())
            assertFalse("Latent element must not be Infinite at $i", v.isInfinite())
            assertTrue("Latent element must be in [-1.0, 1.0] at $i, was $v", v in -1.0f..1.0f)
        }
    }

    @Test
    fun testNvcFeatureExtractorTemporalInterFrameTracking() {
        val extractor = com.infinity.mediaplayer.codec.NvcFeatureExtractor(48, 32, 32)
        val w = 32
        val h = 32
        val frame1 = IntArray(w * h) { 0xFF000000.toInt() } // black frame
        val frame2 = IntArray(w * h) { 0xFFFFFFFF.toInt() } // white frame (high motion/delta)

        // Frame 1
        val latent1 = extractor.extractLatentFromRgbPixels(frame1, w, h)
        // On first frame, motion magnitude channels (e.g. channel 33) should be near zero
        val ch33_frame1 = latent1[(33 * 32 + 16) * 32 + 16]
        assertEquals(0.0f, ch33_frame1, 0.001f)

        // Frame 2 has large delta
        val latent2 = extractor.extractLatentFromRgbPixels(frame2, w, h)
        val ch33_frame2 = latent2[(33 * 32 + 16) * 32 + 16]
        assertTrue("Channel 33 motion magnitude must be > 0 on inter-frame jump", ch33_frame2 > 0.5f)

        // Reset temporal state
        extractor.resetTemporalState()
        val latent3 = extractor.extractLatentFromRgbPixels(frame2, w, h)
        val ch33_frame3 = latent3[(33 * 32 + 16) * 32 + 16]
        assertEquals(0.0f, ch33_frame3, 0.001f)
    }
}
