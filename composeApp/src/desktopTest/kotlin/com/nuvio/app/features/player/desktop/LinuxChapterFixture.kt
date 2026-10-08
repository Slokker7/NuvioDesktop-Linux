package com.nuvio.app.features.player.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Small, sparse MJPEG Matroska: real chapters/video without an external encoder. */
internal object LinuxChapterFixture {
    data class Chapter(val seconds: Double, val title: String?)

    val chapters = listOf(
        Chapter(0.0, "  Chapter \"One\"  "),
        Chapter(0.125, "back\\slash\nline"),
        Chapter(0.126, "\u2003Été Ελληνικά Кириллица 🟦\u2003"),
        Chapter(1.0, "duplicate"), Chapter(1.0, "duplicate"),
        Chapter(1.5, ""), Chapter(1.75, null), Chapter(2.0, " \t\n "),
        Chapter(2.5, "long " + "é\"\\".repeat(1000)),
    ) + List(100) { Chapter(3.0 + it * 0.001, "Chapter $it") } + listOf(
        Chapter(3600.123456, "After one hour"), Chapter(3601.999, "Near EOF"),
    )

    fun write(path: Path, chapters: List<Chapter> = this.chapters) {
        val header = element(0x1A45DFA3, uint(0x4286, 1), uint(0x42F7, 1),
            uint(0x42F2, 4), uint(0x42F3, 8), string(0x4282, "matroska"),
            uint(0x4287, 4), uint(0x4285, 2))
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000),
            element(0x4489, ByteBuffer.allocate(8).putDouble(3602000.0).array()),
            string(0x4D80, "Nuvio test"), string(0x5741, "Nuvio test"))
        val tracks = element(0x1654AE6B, element(0xAE, uint(0xD7, 1), uint(0x73C5, 1),
            uint(0x83, 1), string(0x86, "V_MJPEG"),
            element(0xE0, uint(0xB0, 64), uint(0xBA, 64))))
        val chapterData = element(0x1043A770, element(0x45B9,
            *chapters.mapIndexed { index, chapter ->
                element(0xB6, uint(0x73C4, index + 1L),
                    uint(0x91, Math.round(chapter.seconds * 1_000_000_000)),
                    chapter.title?.let { element(0x80, string(0x85, it), string(0x437C, "eng")) }
                        ?: byteArrayOf())
            }.toTypedArray()))
        val frames = longArrayOf(0, 1000, 2000, 3000, 3600000, 3601900).mapIndexed { index, time ->
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().also { graphics ->
                graphics.color = Color(40 + index * 20, 80, 120)
                graphics.fillRect(0, 0, 64, 64)
                graphics.dispose()
            }
            val jpeg = ByteArrayOutputStream().also { check(ImageIO.write(image, "jpeg", it)) }.toByteArray()
            element(0x1F43B675, uint(0xE7, time),
                element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + jpeg))
        }
        Files.write(path, header + element(0x18538067, info, tracks, chapterData, *frames.toTypedArray()))
    }

    private fun string(id: Long, value: String) = element(id, value.toByteArray(Charsets.UTF_8))
    private fun uint(id: Long, value: Long) = element(id, bytes(value))
    private fun bytes(value: Long, length: Int = (1..8).first { it == 8 || value ushr (it * 8) == 0L }) =
        ByteArray(length) { (value ushr ((length - it - 1) * 8)).toByte() }

    private fun element(id: Long, vararg parts: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().also { stream -> parts.forEach(stream::write) }.toByteArray()
        val size = payload.size.toLong()
        val length = (1..8).first { size < (1L shl (7 * it)) - 1 }
        return bytes(id) + bytes(size or (1L shl (7 * length)), length) + payload
    }
}
