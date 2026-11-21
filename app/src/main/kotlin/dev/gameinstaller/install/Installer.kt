package dev.gameinstaller.install

import android.content.Context
import android.os.StatFs
import dev.gameinstaller.PathUtil
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

    fun install(jobs: List<ArchiveJob>, destDir: File) {
        InstallState.reset()
        InstallState.update(running = true, phase = "Підготовка…", percent = 0)
        try {
            destDir.mkdirs()
            require(destDir.isDirectory && destDir.canWrite()) { "Немає доступу на запис у ${destDir.path}" }

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
                    val code = run(listOf("x", "-o+", "-p${j.password}", "-w${ctx.cacheDir.path}", "-dp${destDir.path}", j.path)) { line ->
                        val m = extracting.find(line)
                        if (m != null) {
                            val d = done.addAndGet(m.groupValues[2].toLong())
                            val pct = if (grand > 0) (d * 100 / grand).toInt().coerceIn(0, 99) else 0
                            InstallState.log(m.groupValues[1])
                            InstallState.update(percent = pct)
                        } else if (line.startsWith("ERROR") || line.contains("failed", true)) {
                            InstallState.log(line)
                        }
                    }
                    if (!cancelled && code != 0) {
                        failure.compareAndSet(null, "Помилка розпаковки $name (код $code). Дивіться журнал.")
                        cancel()
                    } else if (!cancelled) InstallState.log("✔ $name")
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
        }
    }
}
