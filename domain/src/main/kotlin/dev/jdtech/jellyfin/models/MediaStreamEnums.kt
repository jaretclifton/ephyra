package dev.jdtech.jellyfin.models

// The app's own stream enums (#741), with the constants of Jellyfin SDK 1.9.4. The server adapter
// converts the SDK's by constant name, and a test there fails when the SDK gains a constant.

/** What a stream of a media source carries. */
// The constant names are what Room has stored in mediastreams.type. Do not rename one.
enum class MediaStreamType {
    AUDIO,
    VIDEO,
    SUBTITLE,
    EMBEDDED_IMAGE,
    DATA,
    LYRIC,
}

/** How the server delivers a subtitle stream. */
enum class SubtitleDeliveryMethod {
    ENCODE,
    EMBED,
    EXTERNAL,
    HLS,
    DROP,
}

/** The dynamic range of a video stream. [serialName] is the server's name for it. */
// The constant names are what Room has stored in mediastreams.videoRangeType. Do not rename one.
enum class VideoRangeType(val serialName: String) {
    UNKNOWN("Unknown"),
    SDR("SDR"),
    HDR10("HDR10"),
    HLG("HLG"),
    DOVI("DOVI"),
    DOVI_WITH_HDR10("DOVIWithHDR10"),
    DOVI_WITH_HLG("DOVIWithHLG"),
    DOVI_WITH_SDR("DOVIWithSDR"),
    DOVI_WITH_EL("DOVIWithEL"),
    DOVI_WITH_HDR10_PLUS("DOVIWithHDR10Plus"),
    DOVI_WITH_ELHDR10_PLUS("DOVIWithELHDR10Plus"),
    DOVI_INVALID("DOVIInvalid"),
    HDR10_PLUS("HDR10Plus"),
}
