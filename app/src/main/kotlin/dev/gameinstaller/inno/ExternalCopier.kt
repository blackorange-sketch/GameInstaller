package dev.gameinstaller.inno

import dev.gameinstaller.install.SourceFs
import dev.gameinstaller.install.globRegex
import dev.gameinstaller.install.normRel
import java.io.File

/**
 * «Зовнішні» файли Inno: записи без даних у контейнері, які інсталятор копіює з диска ({src}\…) у {app}.
 * Саме так репаки кладуть exe, документи й підпапки. Умови (Check, Components, Tasks) не обчислюються.
 */
object ExternalCopier {
    private const val FLAG_RECURSE = 1 shl 21

    fun plan(setup: InnoSetup): List<FileEntry> = setup.files.filter { f ->
        (f.location < 0 || f.location >= setup.data.size) &&
            f.source.startsWith("{src}", ignoreCase = true) && f.dest.startsWith("{app}", ignoreCase = true)
    }

    fun copyAll(
        setup: InnoSetup, fs: SourceFs, destDir: File,
        log: (String) -> Unit, cancelled: () -> Boolean,
    ): Int {
        var count = 0
        for (f in plan(setup)) {
            if (cancelled()) return count
            val srcRel = normRel(f.source.substring(5))
            val destRel = f.dest.substring(5)
            val destIsDir = destRel.endsWith("\\") || destRel.endsWith("/")
            val destNorm = normRel(destRel)
            val last = srcRel.substringAfterLast('/')
            val parts: List<Pair<String, String>>   // (шлях у джерелі, шлях відносно папки гри)
            if ('*' in last || '?' in last) {
                val dir = srcRel.substringBeforeLast('/', "")
                parts = fs.find(dir, last, f.flags and FLAG_RECURSE != 0).map { rel ->
                    val full = if (dir.isEmpty()) rel else "$dir/$rel"
                    full to (if (destNorm.isEmpty()) rel else "$destNorm/$rel")
                }
            } else {
                if (!fs.exists(srcRel)) continue
                parts = listOf(srcRel to (if (destIsDir) (if (destNorm.isEmpty()) last else "$destNorm/$last") else destNorm))
            }
            for ((src, rel) in parts) {
                if (cancelled()) return count
                val out = File(destDir, rel)
                require(out.canonicalPath.startsWith(destDir.canonicalPath)) { "Небезпечний шлях: $rel" }
                fs.copyTo(src, out, cancelled) { }
                log(rel)
                count++
            }
        }
        return count
    }
}
