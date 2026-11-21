package dev.gameinstaller.inno

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Розбір таблиць файлів Inno Setup 5.5.x (unicode). Порт tools/inno_extract.py.
 * Структури за innoextract (Daniel Scharrer, ліцензія zlib/libpng).
 */
class FileEntry(val source: String, val dest: String, val location: Long, val flags: Int)

class DataEntry(
    val slice: Int, val offset: Long, val foffset: Long, val size: Long, val csize: Long,
    val sha1: ByteArray, val flags: Int,
) {
    val callFilter get() = flags and 0x10 != 0
    val encrypted get() = flags and 0x40 != 0
    val compressed get() = flags and 0x80 != 0
}

class InnoSetup(
    val slicesPerDisk: Int, val compression: Int,
    val files: List<FileEntry>, val data: List<DataEntry>,
) {
    /** Файли, що ставляться в папку гри ({app}) і лежать у самому інсталяторі. */
    fun appFiles(): List<Pair<String, DataEntry>> = files.mapNotNull { f ->
        val d = f.dest
        if (f.location < 0 || f.location >= data.size) return@mapNotNull null
        if (!d.startsWith("{app}\\", ignoreCase = true)) return@mapNotNull null
        d.substring(6) to data[f.location.toInt()]
    }
}

object InnoFiles {
    private class R(val b: ByteBuffer) {
        fun u8() = b.get().toInt() and 255
        fun u16() = b.short.toInt() and 0xFFFF
        fun u32() = b.int.toLong() and 0xFFFFFFFFL
        fun i32() = b.int
        fun u64() = b.long
        fun skip(n: Int) { b.position(b.position() + n) }
        fun raw(n: Int) = ByteArray(n).also { b.get(it) }
        fun bytes(): ByteArray = raw(u32().toInt())
        fun str() = String(bytes(), Charsets.UTF_16LE)
        fun flags(bits: Int): Int {
            var n = (bits + 7) / 8
            if (n == 3) n = 4
            var v = 0L
            for (i in 0 until n) v = v or (u8().toLong() shl (8 * i))
            return v.toInt()
        }
    }

    private fun reader(a: ByteArray) = R(ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN))
    private fun winver(r: R) = r.skip(20)  // діапазон версій Windows (min + only-below)

    fun parse(r1: InnoHeader.Result): InnoSetup {
        val r = reader(r1.header)
        repeat(31) { r.bytes() }                                   // рядки заголовка (назви, URL, ліцензія, скрипт)
        val cnt = IntArray(6) { r.u32().toInt() }                  // language, message, permission, type, component, task
        val dirs = r.u32().toInt(); val nFiles = r.u32().toInt(); val nData = r.u32().toInt()
        repeat(7) { r.u32() }                                      // icon, ini, registry, delete, uninstall_delete, run, uninstall_run
        winver(r)
        r.u32(); r.u32(); r.u32()                                  // кольори
        r.skip(20 + 8 + 8)                                         // хеш і сіль пароля, extra disk space
        val spd = r.u32().toInt()
        r.skip(1 + 1 + 1 + 2)
        val compression = r.u8()
        r.skip(2 + 2 + 8)
        r.flags(49)

        repeat(cnt[0]) { repeat(10) { r.bytes() }; r.u32(); r.skip(16); r.skip(1) }
        repeat(cnt[1]) { r.bytes(); r.bytes(); r.i32() }
        repeat(cnt[2]) { r.bytes() }
        repeat(cnt[3]) { repeat(4) { r.bytes() }; winver(r); r.skip(2); r.u64() }
        repeat(cnt[4]) { repeat(5) { r.bytes() }; r.u64(); r.i32(); r.skip(1); winver(r); r.skip(1); r.u64() }
        repeat(cnt[5]) { repeat(6) { r.bytes() }; r.i32(); r.skip(1); winver(r); r.skip(1) }
        repeat(dirs) { repeat(7) { r.bytes() }; r.u32(); winver(r); r.skip(2); r.skip(1) }

        val files = ArrayList<FileEntry>(nFiles)
        repeat(nFiles) {
            val source = r.str()
            val dest = r.str()
            r.bytes(); r.bytes()               // install_font_name, strong_assembly_name
            repeat(6) { r.bytes() }            // умови (components, tasks, languages, check, after/before install)
            winver(r)
            val loc = r.u32()
            r.u32(); r.u64(); r.skip(2)        // attributes, external_size, permission
            val fl = r.flags(32); r.u8()       // прапорці та тип
            files.add(FileEntry(source, dest, loc, fl))
        }

        val d = reader(r1.data)
        val data = ArrayList<DataEntry>(nData)
        repeat(nData) {
            val first = d.u32().toInt(); d.u32()
            val offset = d.u32()
            val foffset = d.u64(); val size = d.u64(); val csize = d.u64()
            val sha1 = d.raw(20)
            d.skip(8 + 8)                      // timestamp, версія файлу
            data.add(DataEntry(first, offset, foffset, size, csize, sha1, d.flags(9)))
        }
        return InnoSetup(spd, compression, files, data)
    }
}
