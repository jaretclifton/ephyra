/*
 * Codec probe adapted from jellyfin-android
 * (https://github.com/jellyfin/jellyfin-android),
 * file: app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt
 *
 * jellyfin-android is licensed under GPL-2.0; Findroid is likewise GPL-licensed,
 * so this port is compatible.
 *
 * The probing half of the profile builder (#752): what this device can decode, in plain types.
 * The Jellyfin profile is assembled from it in JellyfinDeviceProfiles.
 */
package dev.jdtech.jellyfin.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display
import dev.jdtech.jellyfin.domain.ports.DeviceCodecs
import kotlin.math.max

class DeviceCodecProbe(context: Context) : DeviceCodecs {

    private val displayManager = context.getSystemService(DisplayManager::class.java)

    // Probed on first use, not at construction: building the profile is only needed for playback
    // and downloads, and the probe walks every decoder (#250).
    private val probedCodecs: ProbedCodecs by lazy { probeCodecs() }

    /**
     * A DV decoder only helps on a display that takes DV: a TV box can have the decoder and be
     * plugged into an HDR10 TV. Not probed once like the decoders, because that box can be plugged
     * into another TV at any time.
     */
    override fun displaySupportsDolbyVision(): Boolean {
        val capabilities = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities
        // Soft-deprecated like in DeviceCapabilityReportBuilder, same reason.
        @Suppress("DEPRECATION") val hdrTypes = capabilities?.supportedHdrTypes ?: return false
        return Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes
    }

    /**
     * The [MediaCodecList] probe result, probed on first use. Also surfaced by the diagnostic
     * capability report and the About screen.
     */
    override fun probedCodecsSnapshot(): ProbedCodecs = probedCodecs

    /** Probes the device's decoders via [MediaCodecList] (REGULAR_CODECS). */
    private fun probeCodecs(): ProbedCodecs {
        val videoCodecs: MutableMap<String, DeviceCodec.Video> = HashMap()
        val audioCodecs: MutableMap<String, DeviceCodec.Audio> = HashMap()
        val dolbyVisionLevels: MutableMap<Int, Int> = HashMap()
        val androidCodecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in androidCodecs.codecInfos) {
            if (codecInfo.isEncoder) continue

            for (mimeType in codecInfo.supportedTypes) {
                val codec =
                    try {
                        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                        if (
                            mimeType.equals(
                                MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
                                ignoreCase = true,
                            )
                        ) {
                            collectDolbyVisionLevels(capabilities, dolbyVisionLevels)
                        }
                        DeviceCodec.from(capabilities)
                    } catch (_: Exception) {
                        // Some OEM codecs throw on getCapabilitiesForType — skip them.
                        null
                    } ?: continue
                when (codec) {
                    is DeviceCodec.Video -> {
                        videoCodecs[codec.name] =
                            videoCodecs[codec.name]?.mergeCodec(codec) ?: codec
                    }
                    is DeviceCodec.Audio -> {
                        audioCodecs[codec.name] =
                            audioCodecs[codec.name]?.mergeCodec(codec) ?: codec
                    }
                }
            }
        }
        return ProbedCodecs(
            videoCodecProfiles = videoCodecs.mapValues { (_, codec) -> codec.profiles },
            audioCodecs = audioCodecs.keys.toSet(),
            dolbyVisionProfiles =
                dolbyVisionLevels
                    .mapNotNull { (profile, level) ->
                        CodecHelpers.getDolbyVisionLimit(level)?.let { limit -> profile to limit }
                    }
                    .toMap(),
        )
    }

    /** Keeps the highest level per Dolby Vision profile across decoders in [levels]. */
    private fun collectDolbyVisionLevels(
        capabilities: CodecCapabilities,
        levels: MutableMap<Int, Int>,
    ) {
        // ExoPlayer can't use a secure-only decoder for a clear stream.
        if (capabilities.isFeatureRequired(CodecCapabilities.FEATURE_SecurePlayback)) return

        for (profileLevel in capabilities.profileLevels) {
            val profile = CodecHelpers.getDolbyVisionProfile(profileLevel.profile) ?: continue
            // The level constants are bit flags in ascending order.
            levels[profile] = max(levels[profile] ?: 0, profileLevel.level)
        }
    }
}
