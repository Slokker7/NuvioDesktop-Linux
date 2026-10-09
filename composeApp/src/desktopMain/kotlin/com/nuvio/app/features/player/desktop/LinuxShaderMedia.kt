package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopVideoSourceSize

/** One decoded video-params observation: never pair a new size with an old classification. */
internal data class LinuxShaderMedia(val confirmedSdr: Boolean, val size: DesktopVideoSourceSize?) {
    companion object {
        const val EVENT_PREFIX = "linuxShaderMedia/"

        fun fromEvent(event: String, packedSize: Double): LinuxShaderMedia {
            val fields = event.removePrefix(EVENT_PREFIX).split('/')
            val gamma = fields.getOrNull(0)
            val primaries = fields.getOrNull(1)
            val matrix = fields.getOrNull(2)
            // A whitelist is intentional: missing, new or unrecognised metadata is not SDR.
            // PQ/HLG, BT.2020 and Dolby Vision never qualify, even for a session force.
            val sdr = event.startsWith(EVENT_PREFIX) && fields.size == 3 &&
                gamma in setOf("bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2",
                    "gamma2.4", "gamma2.6", "gamma2.8") &&
                primaries in setOf("bt.709", "bt.601-525", "bt.601-625", "bt.470m", "smpte-240m") &&
                matrix in setOf("bt.709", "bt.601", "smpte-240m", "rgb")
            return LinuxShaderMedia(sdr, DesktopVideoSourceSize.unpack(packedSize))
        }
    }
}
