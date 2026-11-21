package dev.gameinstaller.inno

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Читає заголовок Inno Setup 5.x (unicode) з Setup.exe. Порт tools/inno_probe.py. */
object InnoHeader {
    private val LDR_PREFIX = "rDlPtS".toByteArray(Charsets.US_ASCII)
    private const val SETUP_ID_LEN = 64

    class Result(val setupId: String, val header: ByteArray)

    fun load(exe: ByteArray): Result {
        val at = indexOf(exe, LDR_PREFIX)
        require(at >= 0) { "Не Inno Setup: немає таблиці rDlPtS" }
        val bb = ByteBuffer.wrap(exe).order(ByteOrder.LITTLE_ENDIAN)
        val headerOffset = bb.getInt(at + 12 + 5 * 4).toLong() and 0xFFFFFFFFL
        val off = headerOffset.toInt()
        val id = String(exe, off, SETUP_ID_LEN, Charsets.US_ASCII).substringBefore('\u0000')
        return Result(id, readBlock(exe, off + SETUP_ID_LEN))
    }

    /** Блок: crc32(4), stored size(4), flag(1); далі шматки по 4096 байт + crc32(4). */
    private fun readBlock(d: ByteArray, off: Int): ByteArray {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val size = bb.getInt(off + 4)
        val compressed = d[off + 8].toInt() != 0
        var p = off + 9
        var rem = size
        val raw = ByteArrayOutputStream()
        while (rem > 0) {
            val n = minOf(rem, 4100)
            raw.write(d, p + 4, n - 4)
            p += n
            rem -= n
        }
        val data = raw.toByteArray()
        if (!compressed) return data
        val props = data[0].toInt() and 0xFF
        val dict = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val input = ByteArrayInputStream(data, 5, data.size - 5)
        val out = ByteArrayOutputStream()
        // Розмір після розпаковки невідомий (кінцевого маркера в Inno немає),
        // тому читаємо до кінця вхідних даних і зберігаємо те, що встигли.
        try {
            LZMAInputStream(input, -1L, props.toByte(), dict).use { s ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        } catch (_: IOException) {
            // очікуваний кінець потоку без маркера
        }
        return out.toByteArray()
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
