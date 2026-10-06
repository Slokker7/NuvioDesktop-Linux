package com.nuvio.app.features.player.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin

/** Five seconds of two PCM tracks in Matroska, generated without an encoder or binary asset. */
internal object LinuxAudioTrackFixture {
    const val title = "  Original \"Mono\" \\\n🟦  "

    fun write(path: Path) {
        val header = element(0x1A45DFA3,
            uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
            element(0x4282, "matroska".toByteArray()), uint(0x4287, 4), uint(0x4285, 2))
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000),
            element(0x4489, double(5000.0)), element(0x4D80, "Nuvio test".toByteArray()),
            element(0x5741, "Nuvio test".toByteArray()))
        val tracks = element(0x1654AE6B, *Array(2) { index ->
            element(0xAE, uint(0xD7, (index + 1).toLong()), uint(0x73C5, (index + 1).toLong()),
                uint(0x83, 2), uint(0x88, if (index == 0) 1 else 0),
                element(0x86, "A_PCM/INT/LIT".toByteArray()),
                element(0x536E, (if (index == 0) title else "").toByteArray()),
                element(0x22B59C, (if (index == 0) "eng" else "fra").toByteArray()),
                element(0xE1, element(0xB5, double(8000.0)),
                    uint(0x9F, (index + 1).toLong()), uint(0x6264, 16)))
        })
        val cluster = ByteArrayOutputStream()
        cluster.write(uint(0xE7, 0))
        repeat(250) { frame ->
            repeat(2) { track ->
                val pcm = ByteBuffer.allocate(160 * (track + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(160) { sample ->
                    val value = (2048 * sin(2 * Math.PI * (440 * (track + 1)) * (frame * 160 + sample) / 8000)).toInt().toShort()
                    repeat(track + 1) { pcm.putShort(value) }
                }
                // SimpleBlock: track VINT, signed relative timestamp, keyframe flag, PCM.
                val block = ByteBuffer.allocate(4 + pcm.capacity())
                    .put((0x81 + track).toByte()).putShort((frame * 20).toShort()).put(0x80.toByte())
                    .put(pcm.array()).array()
                cluster.write(element(0xA3, block))
            }
        }
        Files.write(path, header + element(0x18538067, info, tracks, element(0x1F43B675, cluster.toByteArray())))
    }

    private fun double(value: Double) = ByteBuffer.allocate(8).putDouble(value).array()
    private fun uint(id: Long, value: Long) = element(id, bytes(value))
    private fun bytes(value: Long, length: Int = (1..8).first { it == 8 || value ushr (it * 8) == 0L }) =
        ByteArray(length) { (value ushr ((length - it - 1) * 8)).toByte() }

    private fun element(id: Long, vararg parts: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        parts.forEach { payload.write(it) }
        val size = payload.size().toLong()
        // All-ones VINT is reserved for unknown sizes; use a longer encoding then.
        val length = (1..8).first { size < (1L shl (7 * it)) - 1 }
        return bytes(id) + bytes(size or (1L shl (7 * length)), length) + payload.toByteArray()
    }
}
