package dev.gameinstaller.install

import dev.gameinstaller.iso.IsoSet
import java.io.File
import java.io.IOException

/**
 * Джерело файлів інсталятора: звичайна папка або набір образів ISO.
 * Усі шляхи відносні, з '/' як роздільником, регістр не важливий (як у Windows).
 */
interface SourceFs {
    fun exists(rel: String): Boolean
    fun size(rel: String): Long
    /** Файли в [dir] за шаблоном * і ?; шляхи в результаті відносні до [dir]. */
    fun find(dir: String, pattern: String, recurse: Boolean): List<String>
    fun readBytes(rel: String): ByteArray
    fun copyTo(rel: String, dest: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit)
    /** Справжній файл на диску, якщо джерело це папка. */
    fun localFile(rel: String): File?
}

fun normRel(s: String): String = s.replace('\\', '/').trim('/').replace(Regex("/+"), "/")

fun globRegex(pattern: String): Regex =
    Regex("^" + pattern.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("") + "$", RegexOption.IGNORE_CASE)

class FolderFs(private val root: File) : SourceFs {
    private fun child(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun resolve(rel: String): File? {
        var cur = root
        for (part in normRel(rel).split('/').filter { it.isNotEmpty() }) cur = child(cur, part) ?: return null
        return cur
    }

    override fun exists(rel: String) = resolve(rel)?.isFile == true
    override fun size(rel: String) = resolve(rel)?.length() ?: 0L
    override fun localFile(rel: String) = resolve(rel)?.takeIf { it.isFile }
    override fun readBytes(rel: String) = (localFile(rel) ?: throw IOException("Немає файлу $rel")).readBytes()

    override fun find(dir: String, pattern: String, recurse: Boolean): List<String> {
        val base = resolve(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        val re = globRegex(pattern)
        val out = ArrayList<String>()
        fun walk(d: File, prefix: String) {
            for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
                if (f.isDirectory) { if (recurse) walk(f, prefix + f.name + "/") }
                else if (re.matches(f.name)) out.add(prefix + f.name)
            }
        }
        walk(base, "")
        return out
    }

    override fun copyTo(rel: String, dest: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        val src = localFile(rel) ?: throw IOException("Немає файлу $rel")
        dest.parentFile?.mkdirs()
        src.inputStream().use { i -> dest.outputStream().use { o ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (cancelled()) throw InterruptedException()
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); onBytes(n.toLong())
            }
        } }
    }
}

/** [inner]: папка в образах, відносно якої рахуються шляхи (там, де лежить інсталятор). */
class IsoFs(private val set: IsoSet, inner: String) : SourceFs {
    private val prefix = normRel(inner).let { if (it.isEmpty()) "" else "$it/" }

    private fun entry(rel: String) = set.entries[(prefix + normRel(rel)).lowercase()]
    override fun exists(rel: String) = entry(rel) != null
    override fun size(rel: String) = entry(rel)?.size ?: 0L
    override fun localFile(rel: String): File? = null
    override fun readBytes(rel: String): ByteArray {
        val e = entry(rel) ?: throw IOException("В образах немає $rel")
        return e.image.readAll(e)
    }

    override fun find(dir: String, pattern: String, recurse: Boolean): List<String> {
        val d = normRel(dir)
        val base = (prefix + d).let { if (d.isEmpty()) prefix else "$it/" }.lowercase()
        val re = globRegex(pattern)
        return set.entries.values.mapNotNull { e ->
            val p = e.path
            if (!p.lowercase().startsWith(base)) return@mapNotNull null
            val rest = p.substring(base.length)
            if (!recurse && '/' in rest) return@mapNotNull null
            if (re.matches(rest.substringAfterLast('/'))) rest else null
        }.sorted()
    }

    override fun copyTo(rel: String, dest: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        val e = entry(rel) ?: throw IOException("В образах немає $rel")
        e.image.copyTo(e, dest, cancelled, onBytes)
    }
}

/** Результат пошуку інсталятора: шлях або опис того, що не підійшло. */
class InstallerSearch(val rel: String?, val report: String)

/** Шукає виконуваний файл Inno Setup: спочатку setup.exe/start.exe у корені, потім будь-які .exe (мілкі першими). */
fun findInstallerExe(fs: SourceFs, check: (ByteArray) -> String?): InstallerSearch {
    val exes = fs.find("", "*.exe", false).sortedWith(compareBy({
        when (it.lowercase()) { "setup.exe" -> 0; "start.exe" -> 1; else -> 2 }
    }, { it.count { c -> c == '/' } }, { it }))
    val rep = StringBuilder()
    for (name in exes.take(12)) {
        val size = fs.size(name)
        if (size > 400L * 1024 * 1024) { rep.append("$name: завеликий (${size shr 20} МБ); "); continue }
        val err = try { check(fs.readBytes(name)) } catch (e: Throwable) { "читання: ${e.message}" }
        if (err == null) return InstallerSearch(name, "")
        rep.append("$name (${size shr 10} КБ): $err; ")
    }
    if (exes.isEmpty()) rep.append("жодного .exe")
    return InstallerSearch(null, rep.toString())
}

/** Той самий [fs], але шляхи рахуються від підпапки [dir] (де лежить інсталятор). */
class SubFs(private val fs: SourceFs, dir: String) : SourceFs {
    private val d = normRel(dir)
    private fun p(rel: String) = if (d.isEmpty()) normRel(rel) else "$d/${normRel(rel)}"
    override fun exists(rel: String) = fs.exists(p(rel))
    override fun size(rel: String) = fs.size(p(rel))
    override fun find(dir: String, pattern: String, recurse: Boolean) = fs.find(p(dir).trimEnd('/'), pattern, recurse)
    override fun readBytes(rel: String) = fs.readBytes(p(rel))
    override fun copyTo(rel: String, dest: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) = fs.copyTo(p(rel), dest, cancelled, onBytes)
    override fun localFile(rel: String) = fs.localFile(p(rel))
}
