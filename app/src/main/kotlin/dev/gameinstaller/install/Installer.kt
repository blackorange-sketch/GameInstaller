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
            val jobs = jobsIn.map { ArchiveJob(local(it.path).path, it.password) }

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
