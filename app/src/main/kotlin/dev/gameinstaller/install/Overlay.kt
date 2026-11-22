package dev.gameinstaller.install

import java.io.File

/**
 * Диски R.G. Mechanics (C&C 3): у корені лежать «корені гри» (папки з *.SkuDef: 1/, 2/) і спільні накладки
 * (Movies/, lang-russian/ …), чиї .big мають бути всередині відповідних версійних папок кожного кореня
 * (config.txt там пише `add-big Movies.big` відносно своєї папки). Вливаємо накладки в корені гри.
 * Імена папок зіставляються без урахування регістру (Lang-russian ↔ lang-russian).
 */
object Overlay {
    private fun childCI(dir: File, name: String): File =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: File(dir, name)

    private fun isRoot(d: File) = d.listFiles()?.any { it.isFile && it.name.endsWith(".SkuDef", ignoreCase = true) } == true

    private fun hasVersionConfig(d: File) =
        d.listFiles()?.any { v -> v.isDirectory && File(v, "config.txt").exists() } == true

    fun merge(dest: File, log: (String) -> Unit) {
        val top = dest.listFiles()?.filter { it.isDirectory } ?: return
        val roots = top.filter { isRoot(it) }
        if (roots.isEmpty()) return
        for (ov in top) {
            if (ov in roots) continue
            // у накладці лише версійні підпапки з файлами; у відповідному каталозі кореня має бути config.txt
            val targets = roots.mapNotNull { r ->
                val t = r.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(ov.name, ignoreCase = true) }
                if (t != null && hasVersionConfig(t)) t else null
            }
            if (targets.isEmpty()) continue
            val files = ov.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) continue
            var done = 0
            targets.forEachIndexed { i, target ->
                val last = i == targets.lastIndex
                for (f in files) {
                    val rel = f.relativeTo(ov).path.split(File.separatorChar)
                    var cur = target
                    for (c in rel.dropLast(1)) { cur = childCI(cur, c) }
                    cur.mkdirs()
                    val t = File(cur, rel.last())
                    if (t.exists()) continue
                    val moved = last && f.renameTo(t)
                    if (!moved) { f.copyTo(t, overwrite = false); if (last) f.delete() }
                    done++
                }
            }
            if (done > 0) log("Накладку ${ov.name} влито в: ${targets.joinToString { it.parentFile?.name ?: "?" }} ($done файлів)")
            ov.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        }
    }
}
