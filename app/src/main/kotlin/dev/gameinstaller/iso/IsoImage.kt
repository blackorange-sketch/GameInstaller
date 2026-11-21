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
    /** Причина, чому UDF не прочитався (якщо він є, але не підтримується). */
    var udfNote: String? = null
        private set
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
        val iso = try { loadIso9660(raf) } catch (e: IOException) { null }
        val udf = try { loadUdf(raf) } catch (e: Exception) { udfNote = e.message ?: e.toString(); null }
        // у «мостових» дисках ISO 9660 часто лише заглушка, тож беремо те, де файлів більше
        return when {
            udf != null && (iso == null || udf.size > iso.size) -> udf
            iso != null -> iso
            else -> throw IOException("Невідома файлова система образу (не ISO 9660 і не UDF 1.02/1.5/2.01)")
        }
    }

    private fun loadIso9660(raf: RandomAccessFile): List<IsoEntry> {
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


    // ---------------------------------------------------------------- UDF (1.02 – 2.01, без metadata-розділу)
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u64(b: ByteArray, o: Int) = le32(b, o) or (le32(b, o + 4) shl 32)

    private class Fe(val dir: Boolean, val infoLen: Long, val extents: List<Pair<Long, Long>>, val embedded: ByteArray?)

    private fun loadUdf(raf: RandomAccessFile): List<IsoEntry>? {
        if (256 * SECTOR + SECTOR > raf.length()) return null
        val avdp = readAt(raf, 256 * SECTOR, SECTOR.toInt())
        if (u16(avdp, 0) != 2) return null
        val vdsLen = le32(avdp, 16); val vdsLoc = le32(avdp, 20)
        var partStart = -1L
        var fsdLbn = -1L
        var sec = vdsLoc
        val end = vdsLoc + vdsLen / SECTOR
        while (sec < end) {
            val d = readAt(raf, sec * SECTOR, SECTOR.toInt())
            when (u16(d, 0)) {
                5 -> if (partStart < 0) partStart = le32(d, 188)
                6 -> {
                    require(le32(d, 212) == SECTOR) { "UDF: розмір блоку не 2048" }
                    fsdLbn = le32(d, 252)
                    if (le32(d, 268) > 0 && d[440].toInt() == 2 && String(d, 445, 20, Charsets.ISO_8859_1).contains("Metadata"))
                        throw IOException("UDF 2.50 з metadata-розділом не підтримується")
                }
                8 -> sec = end
            }
            sec++
        }
        if (partStart < 0 || fsdLbn < 0) return null
        val fsd = readAt(raf, (partStart + fsdLbn) * SECTOR, SECTOR.toInt())
        require(u16(fsd, 0) == 256) { "UDF: немає File Set Descriptor" }
        val rootLbn = le32(fsd, 404)
        val out = ArrayList<IsoEntry>()
        udfWalk(raf, partStart, rootLbn, "", out, 0)
        return out
    }

    private fun readFe(raf: RandomAccessFile, partStart: Long, lbn: Long): Fe {
        val sectorPos = (partStart + lbn) * SECTOR
        val d = readAt(raf, sectorPos, SECTOR.toInt())
        val tag = u16(d, 0)
        require(tag == 261 || tag == 266) { "UDF: очікувався File Entry, тег $tag" }
        val ft = d[27].toInt() and 255
        val alloc = u16(d, 34) and 7
        val infoLen = u64(d, 56)
        val lea: Int; val lad: Int; val base: Int
        if (tag == 261) { lea = le32(d, 168).toInt(); lad = le32(d, 172).toInt(); base = 176 } else { lea = le32(d, 208).toInt(); lad = le32(d, 212).toInt(); base = 216 }
        val adStart = base + lea
        if (alloc == 3) {
            return Fe(ft == 4, infoLen, listOf(sectorPos + adStart to infoLen), d.copyOfRange(adStart, adStart + lad))
        }
        val step = if (alloc == 0) 8 else 16
        val ex = ArrayList<Pair<Long, Long>>()
        var p = adStart
        var left = infoLen
        while (p + step <= adStart + lad && left > 0) {
            val lf = le32(d, p)
            val type = (lf ushr 30).toInt()
            val len = lf and 0x3FFFFFFF
            if (len == 0L) break
            if (type == 3) throw IOException("UDF: ланцюжок дескрипторів розміщення не підтримується")
            if (type != 0) throw IOException("UDF: нерозміщені ділянки не підтримуються")
            val lbnAd = le32(d, p + 4)
            val use = minOf(len, left)
            ex.add((partStart + lbnAd) * SECTOR to use)
            left -= use
            p += step
        }
        return Fe(ft == 4, infoLen, ex, null)
    }

    private fun udfWalk(raf: RandomAccessFile, partStart: Long, dirLbn: Long, prefix: String, out: MutableList<IsoEntry>, depth: Int) {
        if (depth > 32) return
        val fe = readFe(raf, partStart, dirLbn)
        val data = if (fe.embedded != null) fe.embedded else {
            val bb = java.io.ByteArrayOutputStream()
            for ((off, len) in fe.extents) bb.write(readAt(raf, off, len.toInt()))
            bb.toByteArray()
        }
        var p = 0
        while (p + 38 <= data.size) {
            if (u16(data, p) != 257) break
            val chars = data[p + 18].toInt() and 255
            val lfi = data[p + 19].toInt() and 255
            val lbn = le32(data, p + 24)
            val liu = u16(data, p + 36)
            val nameOff = p + 38 + liu
            val total = (38 + liu + lfi + 3) and 3.inv()
            val deletedOrParent = chars and 0x04 != 0 || chars and 0x08 != 0
            if (!deletedOrParent && lfi > 0) {
                val comp = data[nameOff].toInt() and 255
                val name = if (comp == 16) String(data, nameOff + 1, lfi - 1, Charsets.UTF_16BE)
                           else String(data, nameOff + 1, lfi - 1, Charsets.ISO_8859_1)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val child = readFe(raf, partStart, lbn)
                if (child.dir) udfWalk(raf, partStart, lbn, path, out, depth + 1)
                else out.add(IsoEntry(path, child.extents, this))
            }
            p += total
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
