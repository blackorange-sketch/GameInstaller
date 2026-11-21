package dev.gameinstaller.inno

import org.tukaani.xz.LZMA2InputStream
import org.tukaani.xz.LZMAInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/** Розпаковує файли {app}, що лежать у самому контейнері Inno (Setup-1a.bin тощо). */
object InnoExtractor {

    fun sliceName(base: String, slice: Int, spd: Int): String =
        if (spd <= 1) "$base-${slice + 1}.bin"
        else "$base-${slice / spd + 1}${('a' + slice % spd)}.bin"

    private fun findIgnoreCase(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private class Limited(inp: InputStream, private var left: Long) : FilterInputStream(inp) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read(); if (b >= 0) left--; return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun chunkStream(raf: RandomAccessFile, compression: Int, e: DataEntry): InputStream {
        raf.seek(e.offset)
        val magic = ByteArray(4); raf.readFully(magic)
        require(magic.contentEquals(byteArrayOf('z'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte(), 0x1a))) { "Пошкоджений чанк" }
        val raw: InputStream = Limited(java.io.BufferedInputStream(java.nio.channels.Channels.newInputStream(raf.channel), 1 shl 16), e.csize)
        if (!e.compressed) return raw
        return when (compression) {
            1 -> InflaterInputStream(raw)
            3 -> {                       // LZMA1: props(1) + dict(4)
                val h = ByteArray(5); readFully(raw, h)
                val dict = ByteBuffer.wrap(h, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                LZMAInputStream(raw, -1L, h[0], dict)
            }
            4 -> {                       // LZMA2: 1 байт розміру словника
                val b = raw.read()
                val maxDict = Int.MAX_VALUE and 15.inv()
                val dict = if (b == 40) maxDict else ((2L or (b and 1).toLong()) shl (b / 2 + 11)).coerceAtMost(maxDict.toLong()).toInt()
                LZMA2InputStream(raw, dict)
            }
            else -> throw IOException("Метод стиснення $compression (bzip2/інші) не підтримується")
        }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var p = 0
        while (p < b.size) { val n = i.read(b, p, b.size - p); if (n < 0) throw IOException("Несподіваний кінець даних"); p += n }
    }

    private fun skipFully(i: InputStream, n0: Long) {
        var n = n0
        val buf = ByteArray(1 shl 16)
        while (n > 0) { val r = i.read(buf, 0, minOf(n, buf.size.toLong()).toInt()); if (r < 0) throw IOException("Несподіваний кінець даних"); n -= r }
    }

    /** Прямий фільтр CALL/JMP (Inno ≥ 5.3.9): повертає розфільтровані байти блоку 64 КБ. */
    private class Exe5309(private val src: InputStream) {
        private var base = 0L
        fun next(out: ByteArray): Int {
            val len = fill(out)
            if (len <= 0) return len
            var i = 0
            while (i < len) {
                val b = out[i].toInt() and 255; i++
                if (b != 0xE8 && b != 0xE9) continue
                val idx = i - 1
                if (idx > 0xFFFB) continue            // інструкція перетинає межу блоку
                if (i + 4 > len) break                // хвіст файлу: залишається як є
                val offset = base + i + 4
                if ((out[i + 3].toInt() and 255).let { it == 0 || it == 0xFF }) {
                    val addr = (offset and 0xFFFFFF).toInt()
                    var rel = (out[i].toInt() and 255) or ((out[i + 1].toInt() and 255) shl 8) or ((out[i + 2].toInt() and 255) shl 16)
                    rel = (rel - addr)
                    out[i] = rel.toByte(); out[i + 1] = (rel shr 8).toByte(); out[i + 2] = (rel shr 16).toByte()
                    if (rel and 0x800000 != 0) out[i + 3] = (out[i + 3].toInt().inv()).toByte()
                }
                i += 4
            }
            base += len
            return len
        }
        private fun fill(b: ByteArray): Int {
            var p = 0
            while (p < b.size) { val n = src.read(b, p, b.size - p); if (n < 0) break; p += n }
            return if (p == 0) -1 else p
        }
    }

    /**
     * @param base ім'я без розширення (з Setup.exe → «Setup»), для пошуку слайсів Setup-1a.bin тощо
     * @return кількість розпакованих файлів
     */
    fun extractApp(
        srcDir: File, base: String, setup: InnoSetup, destDir: File,
        log: (String) -> Unit, cancelled: () -> Boolean,
    ): Int {
        val items = setup.appFiles()
        if (items.isEmpty()) return 0
        // групуємо за чанками (слайс + зсув), щоб розпаковувати кожен чанк один раз
        val byChunk = items.groupBy { it.second.slice to it.second.offset }
        var count = 0
        for ((key, list) in byChunk) {
            if (cancelled()) return count
            val slice = findIgnoreCase(srcDir, sliceName(base, key.first, setup.slicesPerDisk))
                ?: throw IOException("Немає файлу ${sliceName(base, key.first, setup.slicesPerDisk)}")
            val first = list.first().second
            require(!first.encrypted) { "Зашифровані дані Inno не підтримуються" }
            RandomAccessFile(slice, "r").use { raf ->
                val stream = chunkStream(raf, setup.compression, first)
                var pos = 0L
                val sorted = list.sortedBy { it.second.foffset }
                val written = HashMap<Long, File>()      // кілька записів можуть вказувати на одні дані
                for ((rel, e) in sorted) {
                    if (cancelled()) return count
                    val out = File(destDir, rel.replace('\\', '/'))
                    require(out.canonicalPath.startsWith(destDir.canonicalPath)) { "Небезпечний шлях: $rel" }
                    out.parentFile?.mkdirs()
                    val prev = written[e.foffset]
                    if (prev != null) { prev.copyTo(out, overwrite = true); count++; continue }
                    if (e.foffset > pos) { skipFully(stream, e.foffset - pos); pos = e.foffset }
                    require(e.foffset == pos) { "Порядок файлів у чанку порушено" }
                    val sha = MessageDigest.getInstance("SHA-1")
                    out.outputStream().buffered(1 shl 16).use { os ->
                        if (e.callFilter) {
                            val f = Exe5309(Limited(stream, e.size))
                            val buf = ByteArray(0x10000)
                            while (true) { val n = f.next(buf); if (n < 0) break; os.write(buf, 0, n); sha.update(buf, 0, n) }
                        } else {
                            val buf = ByteArray(1 shl 16)
                            var left = e.size
                            while (left > 0) {
                                val n = stream.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n < 0) throw IOException("Несподіваний кінець даних у $rel")
                                os.write(buf, 0, n); sha.update(buf, 0, n); left -= n
                            }
                        }
                    }
                    pos += e.size
                    require(sha.digest().contentEquals(e.sha1)) { "Не збігається SHA-1: $rel" }
                    written[e.foffset] = out
                    count++
                    log(rel)
                }
            }
        }
        return count
    }
}
