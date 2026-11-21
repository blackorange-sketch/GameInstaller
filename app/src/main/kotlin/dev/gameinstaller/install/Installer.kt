package dev.gameinstaller.install

import android.content.Context
import android.os.StatFs
import dev.gameinstaller.PathUtil
import dev.gameinstaller.inno.ExternalCopier
import dev.gameinstaller.inno.InnoExtractor
import dev.gameinstaller.inno.InnoFiles
import dev.gameinstaller.inno.InnoHeader
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ArchiveJob(val path: String, val password: String)


/** Запускає unarc (libunarc.so із nativeLibraryDir) для кожного архіву по черзі. */
class Installer(private val ctx: Context) {
    companion object { const val WORKERS = 2 }
    @Volatile private var cancelled = false
    private val procs = java.util.concurrent.CopyOnWriteArrayList<Process>()

    fun cancel() { cancelled = true; procs.forEach { it.destroy() } }

    private val summary = Regex("""(\d+) files?, (\d+) bytes""")
    private val written = Regex("""^@@ (\d+)""")
    private val extracting = Regex("""^Extracting (.+) \((\d+) bytes\)""")

    private fun unarcBin(): File {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libunarc.so")
        require(f.exists()) { "У збірці немає libunarc.so (unarc). Зберіть APK через CI." }
        return f
    }

    /** Тимчасові файли зовнішніх розпакувальників (srep) — поруч із грою, а не у внутрішній пам'яті. */
    @Volatile private var tmpDir: File? = null

    /** Видаляє залишки тимчасових папок unarc (freearcNNN), якщо його зупинили примусово. */
    private fun cleanTemp(extra: File?) {
        for (d in listOfNotNull(ctx.cacheDir, extra)) {
            d.listFiles()?.forEach { if (it.isDirectory && it.name.startsWith("freearc")) it.deleteRecursively() }
        }
    }

    /** arc.ini для unarc: підключає srep (libsrep.so з nativeLibraryDir) як зовнішній розпакувальник. */
    private fun arcIni(): File? {
        val srep = File(ctx.applicationInfo.nativeLibraryDir, "libsrep.so")
        if (!srep.exists()) return null
        val ini = File(ctx.cacheDir, "arc.ini")
        ini.writeText(
            "[External compressor:srep]\n" +
            "unpackcmd = ${srep.path} -d \$\$arcpackedfile\$\$.tmp \$\$arcdatafile\$\$.tmp\n" +
            "datafile = \$\$arcdatafile\$\$.tmp\n" +
            "packedfile = \$\$arcpackedfile\$\$.tmp\n"
        )
        return ini
    }

    /** @param idleSec якщо > 0 і unarc мовчить довше, процес зупиняється (повертає -2): так видно непідтримуваний метод стиснення. */
    private fun run(args: List<String>, idleSec: Int = 0, onLine: (String) -> Unit): Int {
        val pb = ProcessBuilder(listOf(unarcBin().path) + args).redirectErrorStream(true)
        pb.environment()["TMPDIR"] = (tmpDir ?: ctx.cacheDir).path
        arcIni()?.let { pb.environment()["GI_ARC_INI"] = it.path }
        val p = pb.start()
        procs.add(p)
        val last = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val wd = if (idleSec > 0) kotlin.concurrent.thread(isDaemon = true, name = "unarc-watchdog") {
            try {
                while (true) {
                    Thread.sleep(5000)
                    if (System.currentTimeMillis() - last.get() > idleSec * 1000L) { timedOut.set(true); p.destroyForcibly(); break }
                }
            } catch (_: InterruptedException) { }
        } else null
        BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                last.set(System.currentTimeMillis())
                onLine(line)
            }
        }
        val code = p.waitFor()
        wd?.interrupt()
        procs.remove(p)
        return if (timedOut.get()) -2 else code
    }

    /**
     * @param jobsIn архіви: path відносний до кореня джерела ('/' як роздільник)
     * @param setupRel шлях до exe-інсталятора Inno у джерелі (для файлів контейнера й зовнішніх файлів)
     */
    fun install(jobsIn: List<ArchiveJob>, destDir: File, setupRel: String?, fs: SourceFs) {
        var stage: File? = null
        InstallState.reset()
        InstallState.update(running = true, phase = "Підготовка…", percent = 0)
        try {
            destDir.mkdirs()
            require(destDir.isDirectory && destDir.canWrite()) { "Немає доступу на запис у ${destDir.path}" }
            val stageDir = File(destDir.parentFile ?: destDir, ".gameinstaller_stage")
            val tmpD = File(destDir.parentFile ?: destDir, ".gameinstaller_tmp").also { it.mkdirs() }
            tmpDir = if (tmpD.isDirectory && tmpD.canWrite()) tmpD else null
            cleanTemp(tmpD)

            /** Повертає справжній файл: з папки як є, з образу копіює в тимчасову папку. */
            fun local(rel: String): File {
                fs.localFile(rel)?.let { return it }
                stage = stageDir
                val out = File(stageDir, rel)
                if (out.exists() && out.length() == fs.size(rel)) return out
                require(StatFs((stageDir.parentFile ?: stageDir).path).availableBytes > fs.size(rel)) {
                    "Мало місця для тимчасового файлу ${PathUtil.human(fs.size(rel))} (${rel})"
                }
                InstallState.update(phase = "Копіюю з образу: ${File(rel).name}")
                InstallState.log("ISO → $rel (${PathUtil.human(fs.size(rel))})")
                val total = fs.size(rel).coerceAtLeast(1)
                var copied = 0L
                fs.copyTo(rel, out, { cancelled }) { n ->
                    copied += n
                    InstallState.update(percent = (copied * 100 / total).toInt().coerceIn(0, 99))
                }
                InstallState.update(percent = 0)
                return out
            }

            // 0) файли самого інсталятора: контейнер Inno і «зовнішні» файли з диска; архіви потім перезапишуть однакові
            if (setupRel != null) {
                try {
                    InstallState.update(phase = "Файли з інсталятора…")
                    val parsed = InnoHeader.load(fs.readBytes(setupRel))
                    val setup = InnoFiles.parse(parsed)
                    val innerDir = setupRel.substringBeforeLast('/', "")
                    val base = File(setupRel).nameWithoutExtension
                    val items = setup.appFiles()
                    if (items.isNotEmpty()) {
                        val n = InnoExtractor.extractApp({ name ->
                            fs.find(innerDir, name, false).firstOrNull()?.let { local(if (innerDir.isEmpty()) it else "$innerDir/$it") }
                        }, base, setup, destDir, { InstallState.log("• $it") }, { cancelled })
                        InstallState.log("Файлів із контейнера інсталятора: $n")
                    }
                    val ext = ExternalCopier.plan(setup)
                    if (ext.isNotEmpty()) {
                        InstallState.update(phase = "Копіюю додаткові файли…")
                        val rootFs = if (innerDir.isEmpty()) fs else SubFs(fs, innerDir)
                        val n = ExternalCopier.copyAll(setup, rootFs, destDir, { InstallState.log("• $it") }, { cancelled })
                        InstallState.log("Додаткових файлів скопійовано: $n")
                    }
                } catch (e: InterruptedException) { throw e
                } catch (e: Exception) {
                    if (jobsIn.isEmpty()) throw e
                    InstallState.log("ПОПЕРЕДЖЕННЯ: файли інсталятора не оброблено: ${e.message}")
                }
            }

            // архіви: потрібні локальні файли (з образів копіюємо в тимчасову папку)
            var jobs = jobsIn.map { ArchiveJob(local(it.path).path, it.password) }

            // пароль не знайдено в командному рядку скрипта: підбираємо серед рядків скрипта на найменшому архіві
            if (jobs.isNotEmpty() && jobs.any { it.password.isEmpty() } && setupRel != null) {
                InstallState.update(phase = "Шукаю пароль архівів…")
                val h = InnoHeader.load(fs.readBytes(setupRel))
                val cands = dev.gameinstaller.inno.ScriptScan.scan(h.setupId, h.header).pwCandidates
                val probe = jobs.minByOrNull { File(it.path).length() }!!
                var good: String? = null
                if (run(listOf("v", probe.path)) { } == 0) good = ""
                var tries = 0
                if (good == null) for (c in cands) {
                    if (cancelled) throw InterruptedException()
                    tries++
                    if (run(listOf("v", "-p$c", probe.path)) { } == 0) { good = c; break }
                }
                require(good != null) { "Не вдалося підібрати пароль до ${File(probe.path).name} (перевірено $tries рядків скрипта)." }
                InstallState.log("Пароль архівів підібрано (перевірено рядків: $tries)")
                jobs = jobs.map { ArchiveJob(it.path, if (it.password.isEmpty()) good!! else it.password) }
            }

            // 1) підрахунок загального розміру
            val sizes = LongArray(jobs.size)
            for ((i, j) in jobs.withIndex()) {
                if (cancelled) throw InterruptedException()
                InstallState.update(phase = "Читаю список файлів (${i + 1}/${jobs.size})…")
                var total = -1L
                val code = run(listOf("v", "-p${j.password}", j.path)) { line ->
                    if (line.startsWith("@@M ")) {
                        val m = line.removePrefix("@@M ").substringBefore("+aes")
                        if (m.isNotEmpty() && !m.startsWith("storing")) InstallState.log("метод ${File(j.path).name}: $m")
                    }
                    summary.find(line)?.let { total = it.groupValues[2].toLong() }
                    if (line.startsWith("ERROR")) InstallState.log(line)
                }
                require(code == 0 && total >= 0) { "Не вдалося прочитати ${File(j.path).name} (код $code). Перевірте файл і пароль." }
                sizes[i] = total
            }
            val grand = sizes.sum()
            val free = StatFs(destDir.path).availableBytes
            require(free > grand + (grand / 50)) {
                "Мало місця: потрібно ${PathUtil.human(grand)}, вільно ${PathUtil.human(free)}"
            }
            InstallState.log("Усього розпакувати: ${PathUtil.human(grand)}")
            InstallState.setArchives(jobs.map { File(it.path).name })

            // 2) розпаковка: до WORKERS архівів одночасно, найбільші першими
            val done = java.util.concurrent.atomic.AtomicLong(0)
            val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val order = jobs.indices.sortedByDescending { sizes[it] }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(WORKERS)
            InstallState.update(phase = "Розпаковую (паралельно: $WORKERS)…")
            val futures = order.map { i ->
                pool.submit {
                    if (cancelled || failure.get() != null) return@submit
                    val j = jobs[i]
                    val name = File(j.path).name
                    InstallState.log("▶ $name")
                    var last = 0L
                    val code = run(listOf("x", "-o+", "-p${j.password}", "-w${(tmpDir ?: ctx.cacheDir).path}", "-dp${destDir.path}", j.path), idleSec = 420) { line ->
                        val pm = written.find(line)
                        if (pm != null) {
                            val w = pm.groupValues[1].toLong().coerceAtMost(sizes[i])
                            val d = done.addAndGet(w - last)
                            last = w
                            val pct = if (grand > 0) (d * 100 / grand).toInt().coerceIn(0, 99) else 0
                            InstallState.setArcPercent(i, if (sizes[i] > 0) (w * 100 / sizes[i]).toInt().coerceIn(0, 99) else 0)
                            InstallState.update(percent = pct)
                        } else {
                            val m = extracting.find(line)
                            if (m != null) InstallState.log(m.groupValues[1])
                            else if (line.startsWith("ERROR") || line.contains("failed", true)) InstallState.log(line)
                        }
                    }
                    if (!cancelled && code != 0) {
                        failure.compareAndSet(null, if (code == -2)
                            "Архів $name не відповідає понад 7 хвилин: найімовірніше, метод стиснення не підтримується. Дивіться рядок «метод …» у журналі."
                        else "Помилка розпаковки $name (код $code). Дивіться журнал.")
                        cancel()
                    } else if (!cancelled) { InstallState.setArcPercent(i, 100); InstallState.log("✔ $name") }
                }
            }
            futures.forEach { it.get() }
            pool.shutdown()
            failure.get()?.let { throw IllegalStateException(it) }
            if (cancelled) throw InterruptedException()
            InstallState.update(running = false, phase = "Готово", percent = 100, finished = true)
        } catch (e: InterruptedException) {
            InstallState.update(running = false, phase = "Скасовано", error = "Скасовано користувачем")
        } catch (e: Exception) {
            InstallState.log("ПОМИЛКА: ${e.message}")
            InstallState.update(running = false, phase = "Помилка", error = e.message ?: e.toString())
        } finally {
            stage?.deleteRecursively()
            procs.forEach { it.destroyForcibly() }
            cleanTemp(tmpDir)
            tmpDir?.deleteRecursively(); tmpDir = null
        }
    }

    /** Копіює весь вміст образів у одну папку (як на справжньому диску), щоб запустити інсталятор у Winlator. */
    fun extractIsos(isos: List<File>, destDir: File) {
        InstallState.reset()
        InstallState.update(running = true, phase = "Підготовка…", percent = 0)
        try {
            destDir.mkdirs()
            require(destDir.isDirectory && destDir.canWrite()) { "Немає доступу на запис у ${destDir.path}" }
            val set = dev.gameinstaller.iso.IsoSet(isos.map { dev.gameinstaller.iso.IsoImage(it) })
            val all = set.entries.values.toList()
            require(all.isNotEmpty()) { "В образах немає файлів" }
            val total = all.sumOf { it.size }.coerceAtLeast(1)
            require(StatFs(destDir.path).availableBytes > total) { "Мало місця: потрібно ${PathUtil.human(total)}" }
            var copied = 0L
            for (e in all) {
                if (cancelled) throw InterruptedException()
                val out = File(destDir, e.path)
                require(out.canonicalPath.startsWith(destDir.canonicalPath)) { "Небезпечний шлях: ${e.path}" }
                InstallState.update(phase = "Копіюю: ${e.name}")
                InstallState.log(e.path)
                e.image.copyTo(e, out, { cancelled }) { n ->
                    copied += n
                    InstallState.update(percent = (copied * 100 / total).toInt().coerceIn(0, 99))
                }
            }
            InstallState.log("Скопійовано файлів: ${all.size}")
            InstallState.update(running = false, phase = "Готово", percent = 100, finished = true)
        } catch (e: InterruptedException) {
            InstallState.update(running = false, phase = "Скасовано", error = "Скасовано користувачем")
        } catch (e: Exception) {
            InstallState.log("ПОМИЛКА: ${e.message}")
            InstallState.update(running = false, phase = "Помилка", error = e.message ?: e.toString())
        }
    }
}
