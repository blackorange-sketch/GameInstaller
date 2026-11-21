package dev.gameinstaller.iso

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Один файл усередині ISO (може складатися з кількох екстентів). */
class IsoEntry(val path: String, val extents: List<Pair<Long, Long>>, val image: IsoImage) {
    val size: Long get() = extents.sumOf { it.second }
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
}

/**
 * Читач ISO 9660 (з Joliet для довгих імен). Монтувати образ на Android без root не можна,
 * тому читаємо структуру самі й копіюємо потрібні файли. Не підтримує образи лише з UDF і RAW 2352.
 */
class IsoImage(val file: File) {
    val entries: List<IsoEntry>

    init {
        entries = RandomAccessFile(file, "r").use { load(it) }
    }

    private fun readAt(raf: RandomAccessFile, pos: Long, n: Int): ByteArray {
        val b = ByteArray(n); raf.seek(pos); raf.readFully(b); return b
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 255) or ((b[o + 1].toLong() and 255) shl 8) or ((b[o + 2].toLong() and 255) shl 16) or ((b[o + 3].toLong() and 255) shl 24)

    private fun load(raf: RandomAccessFile): List<IsoEntry> {
        var primary: ByteArray? = null
        var joliet: ByteArray? = null
        var sector = 16L
        while (sector < 64) {
            if ((sector + 1) * SECTOR > raf.length()) break
            val d = readAt(raf, sector * SECTOR, SECTOR.toInt())
            if (String(d, 1, 5, Charsets.US_ASCII) != "CD001") break
            when (d[0].toInt()) {
                1 -> if (primary == null) primary = d
                2 -> {
                    val esc = String(d, 88, 3, Charsets.US_ASCII)
                    if (esc == "%/@" || esc == "%/C" || esc == "%/E") joliet = d
                }
                255 -> sector = 1000
            }
            sector++
        }
        val vd = joliet ?: primary ?: throw IOException("Не знайдено ISO 9660 (образ лише UDF або RAW). Такі поки не підтримуються.")
        val isJoliet = vd === joliet
        val out = ArrayList<IsoEntry>()
        walk(raf, le32(vd, 156 + 2), le32(vd, 156 + 10), "", isJoliet, out, 0)
        return out
    }

    private fun walk(raf: RandomAccessFile, extent: Long, size: Long, prefix: String, joliet: Boolean, out: MutableList<IsoEntry>, depth: Int) {
        if (depth > 32) return
        val data = readAt(raf, extent * SECTOR, size.toInt())
        var p = 0
        var pending: MutableList<Pair<Long, Long>>? = null
        while (p < data.size) {
            val len = data[p].toInt() and 255
            if (len == 0) { p = (p / SECTOR.toInt() + 1) * SECTOR.toInt(); continue }
            val nameLen = data[p + 32].toInt() and 255
            val flags = data[p + 25].toInt() and 255
            val ext = le32(data, p + 2) ; val sz = le32(data, p + 10)
            val rawName = data.copyOfRange(p + 33, p + 33 + nameLen)
            p += len
            if (nameLen == 1 && (rawName[0].toInt() == 0 || rawName[0].toInt() == 1)) continue
            var name = if (joliet) String(rawName, Charsets.UTF_16BE) else String(rawName, Charsets.ISO_8859_1)
            name = name.substringBefore(';')
            if (name.endsWith(".")) name = name.dropLast(1)
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (flags and 2 != 0) {
                walk(raf, ext, sz, path, joliet, out, depth + 1)
            } else {
                val list = pending ?: ArrayList()
                list.add(ext * SECTOR to sz)
                if (flags and 0x80 != 0) { pending = list } else {   // багатоекстентний файл: чекаємо останній запис
                    out.add(IsoEntry(path, list, this)); pending = null
                }
            }
        }
    }

    fun readAll(e: IsoEntry): ByteArray {
        require(e.size < Int.MAX_VALUE) { "Файл завеликий для читання в пам'ять" }
        val out = ByteArray(e.size.toInt())
        var pos = 0
        RandomAccessFile(file, "r").use { raf ->
            for ((off, len) in e.extents) { raf.seek(off); raf.readFully(out, pos, len.toInt()); pos += len.toInt() }
        }
        return out
    }

    /** Копіює файл з ISO на диск. onBytes отримує кількість щойно скопійованих байтів. */
    fun copyTo(e: IsoEntry, dest: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val buf = ByteArray(1 shl 20)
        RandomAccessFile(file, "r").use { raf ->
            dest.outputStream().buffered(1 shl 20).use { os ->
                for ((off, len) in e.extents) {
                    raf.seek(off)
                    var left = len
                    while (left > 0) {
                        if (cancelled()) throw InterruptedException()
                        val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n < 0) throw IOException("Образ обірвано: ${file.name}")
                        os.write(buf, 0, n); onBytes(n.toLong()); left -= n
                    }
                }
            }
        }
    }

    companion object { const val SECTOR = 2048L }
}

/** Набір образів (диск 1, диск 2, сторони диска): файли з однаковими шляхами об'єднуються. */
class IsoSet(val images: List<IsoImage>) {
    val entries: Map<String, IsoEntry> = LinkedHashMap<String, IsoEntry>().also { m ->
        for (img in images) for (e in img.entries) m.putIfAbsent(e.path.lowercase(), e)
    }

    /** Папка зі Setup.exe: найближча до кореня. */
    fun findSetup(): IsoEntry? = entries.values.filter { it.name.equals("setup.exe", true) }.minByOrNull { it.path.count { c -> c == '/' } }

    /** Файли тієї ж папки, потрібні для інсталяції (Setup.exe і *.bin). */
    fun neededIn(innerDir: String): List<IsoEntry> = entries.values.filter {
        it.dir.equals(innerDir, true) && (it.name.equals("setup.exe", true) || it.name.endsWith(".bin", true))
    }
}
