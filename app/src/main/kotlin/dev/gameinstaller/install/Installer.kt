package dev.gameinstaller.install

import android.content.Context
import android.os.StatFs
import dev.gameinstaller.PathUtil
import dev.gameinstaller.inno.InnoExtractor
import dev.gameinstaller.iso.IsoImage
import dev.gameinstaller.iso.IsoSet
import dev.gameinstaller.inno.InnoFiles
import dev.gameinstaller.inno.InnoHeader
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ArchiveJob(val path: String, val password: String)

/** Джерело з образів ISO: innerDir папка зі Setup.exe всередині образів. Імена в ArchiveJob тоді відносні. */
class IsoSource(val isos: List<File>, val innerDir: String)

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

    private fun run(args: List<String>, onLine: (String) -> Unit): Int {
        val pb = ProcessBuilder(listOf(unarcBin().path) + args).redirectErrorStream(true)
        pb.environment()["TMPDIR"] = ctx.cacheDir.path
        val p = pb.start()
        procs.add(p)
        BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                onLine(line)
            }
        }
        val code = p.waitFor()
        procs.remove(p)
        return code
    }

    fun install(jobsIn: List<ArchiveJob>, destDir: File, setupExeIn: File? = null, iso: IsoSource? = null) {
        var stage: File? = null
        InstallState.reset()
        InstallState.update(running = true, phase = "Підготовка…", percent = 0)
        try {
            destDir.mkdirs()
            require(destDir.isDirectory && destDir.canWrite()) { "Немає доступу на запис у ${destDir.path}" }

            // -1) якщо джерело це образи ISO: копіюємо потрібні файли в тимчасову папку поруч із папкою гри
            var jobs = jobsIn
            var setupExe = setupExeIn
            if (iso != null) {
                val set = IsoSet(iso.isos.map { IsoImage(it) })
                val needed = set.neededIn(iso.innerDir)
                require(needed.isNotEmpty()) { "В образах не знайдено файлів інсталятора" }
                val st = File(destDir.parentFile ?: destDir, ".gameinstaller_stage")
                stage = st
                st.deleteRecursively(); st.mkdirs()
                val total = needed.sumOf { it.size }
                require(StatFs(st.path).availableBytes > total) { "Мало місця для тимчасових файлів з образів: потрібно ${PathUtil.human(total)}" }
                var copied = 0L
                for (e in needed) {
                    InstallState.update(phase = "Копіюю з образу: ${e.name}")
                    InstallState.log("ISO → ${e.name} (${PathUtil.human(e.size)})")
                    e.image.copyTo(e, File(st, e.name), { cancelled }) { n ->
                        copied += n
                        InstallState.update(percent = if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 99) else 0)
                    }
                }
                jobs = jobsIn.map { ArchiveJob(File(st, it.path).path, it.password) }
                setupExe = setupExeIn?.let { File(st, it.name) }
                InstallState.update(percent = 0)
            }

            // 0) файли, що лежать у самому контейнері Inno (Setup-1a.bin): ставимо першими, архіви потім перезапишуть однакові
            var innoBytes = 0L
            if (setupExe != null) {
                try {
                    InstallState.update(phase = "Файли з інсталятора…")
                    val parsed = InnoHeader.load(setupExe.readBytes())
                    val setup = InnoFiles.parse(parsed)
                    val items = setup.appFiles()
                    innoBytes = items.map { it.second }.distinctBy { it.slice to (it.offset to it.foffset) }.sumOf { it.size }
                    if (items.isNotEmpty()) {
                        require(StatFs(destDir.path).availableBytes > innoBytes) { "Мало місця для файлів інсталятора" }
                        val n = InnoExtractor.extractApp(setupExe.parentFile!!, setupExe.nameWithoutExtension, setup, destDir,
                            { InstallState.log("• $it") }, { cancelled })
                        InstallState.log("Файлів із контейнера інсталятора: $n")
                    }
                } catch (e: InterruptedException) { throw e
                } catch (e: Exception) {
                    if (jobs.isEmpty()) throw e
                    InstallState.log("ПОПЕРЕДЖЕННЯ: файли з контейнера інсталятора не розпаковано: ${e.message}")
                }
            }

            // 1) підрахунок загального розміру
            val sizes = LongArray(jobs.size)
            for ((i, j) in jobs.withIndex()) {
                if (cancelled) throw InterruptedException()
                InstallState.update(phase = "Читаю список файлів (${i + 1}/${jobs.size})…")
                var total = -1L
                val code = run(listOf("v", "-p${j.password}", j.path)) { line ->
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
                    val code = run(listOf("x", "-o+", "-p${j.password}", "-w${ctx.cacheDir.path}", "-dp${destDir.path}", j.path)) { line ->
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
                        failure.compareAndSet(null, "Помилка розпаковки $name (код $code). Дивіться журнал.")
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
        }
    }
}
