package com.infinity.mediaplayer.audio

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioFormat
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@OptIn(UnstableApi::class)
class AudioSafetyController(private val context: Context) {
    companion object {
        private const val TAG = "AudioSafetyController"

        fun isQualcommDevice(): Boolean {
            val hardware = Build.HARDWARE?.lowercase().orEmpty()
            val board = Build.BOARD?.lowercase().orEmpty()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL?.lowercase().orEmpty() else ""
            return hardware.contains("qcom") || hardware.contains("qualcomm") ||
                   board.contains("msm") || board.contains("sdm") || board.contains("sm") ||
                   board.contains("bengal") || board.contains("lahaina") || board.contains("taro") ||
                   soc.contains("sm") || soc.contains("snapdragon")
        }

        fun isMediaTekDevice(): Boolean {
            val hardware = Build.HARDWARE?.lowercase().orEmpty()
            val board = Build.BOARD?.lowercase().orEmpty()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL?.lowercase().orEmpty() else ""
            return hardware.contains("mt") || hardware.contains("mtk") || hardware.contains("mediatek") ||
                   board.contains("mt") || board.contains("mtk") || board.contains("mediatek") ||
                   soc.contains("dimensity") || soc.contains("helio") || soc.contains("mt")
        }

        fun hasDiracService(): Boolean {
            val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
            val brand = Build.BRAND?.lowercase().orEmpty()
            return manufacturer.contains("oneplus") || manufacturer.contains("oppo") ||
                   manufacturer.contains("realme") || brand.contains("oneplus") ||
                   brand.contains("oppo") || brand.contains("realme")
        }
    }

    var underrunCount = 0
        private set
    var acdbErrorCount = 0
        private set

    val isSafetyActive: Boolean
        get() = isQualcommDevice() || isMediaTekDevice() || hasDiracService()

    val socVendor: String
        get() = when {
            isQualcommDevice() -> "Qualcomm Snapdragon"
            isMediaTekDevice() -> "MediaTek (Dimensity/Helio)"
            Build.HARDWARE?.lowercase().orEmpty().contains("exynos") || Build.BOARD?.lowercase().orEmpty().contains("universal") -> "Samsung Exynos"
            Build.HARDWARE?.lowercase().orEmpty().contains("tensor") || Build.BOARD?.lowercase().orEmpty().contains("gs") -> "Google Tensor"
            else -> "Generic ARM"
        }

    fun isTvDevice(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun recordUnderrun() {
        underrunCount++
    }

    fun recordAcdbError() {
        acdbErrorCount++
    }

    fun resolveEffectiveMode(configuredMode: AudioOutputMode): AudioOutputMode {
        return when (configuredMode) {
            AudioOutputMode.AUTO -> {
                when {
                    isQualcommDevice() || hasDiracService() -> {
                        Log.i(TAG, "Qualcomm SoC or Dirac audio detected ($socVendor). Routing through safe STEREO_PCM to prevent ACDB HAL crashes.")
                        AudioOutputMode.STEREO_PCM
                    }
                    isMediaTekDevice() -> {
                        // On MediaTek mobile devices, BesLoudness/MTK HAL drops unmapped surround channels.
                        // Force clean stereo PCM on handhelds, or allow multichannel on Android TV (Pentonic/MT96xx) with AVR.
                        if (isTvDevice()) {
                            Log.i(TAG, "MediaTek Android TV detected ($socVendor). Enabling MULTICHANNEL_PCM audio path.")
                            AudioOutputMode.MULTICHANNEL_PCM
                        } else {
                            Log.i(TAG, "MediaTek mobile device detected ($socVendor). Selecting STEREO_PCM to avoid MTK audio mixer distortion.")
                            AudioOutputMode.STEREO_PCM
                        }
                    }
                    else -> AudioOutputMode.MULTICHANNEL_PCM
                }
            }
            else -> configuredMode
        }
    }

    fun buildAudioSink(configuredMode: AudioOutputMode): AudioSink {
        val effectiveMode = resolveEffectiveMode(configuredMode)
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)

        when (effectiveMode) {
            AudioOutputMode.STEREO_PCM -> {
                builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            }
            AudioOutputMode.MULTICHANNEL_PCM -> {
                builder.setAudioCapabilities(AudioCapabilities(intArrayOf(AudioFormat.ENCODING_PCM_16BIT), 8))
            }
            AudioOutputMode.PASSTHROUGH -> {
                builder.setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            }
            AudioOutputMode.AUTO -> {
                builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            }
        }

        return builder.build().apply {
            setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        }
    }
}
