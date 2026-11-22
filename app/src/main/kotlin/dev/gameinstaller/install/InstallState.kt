package dev.gameinstaller.install

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Спільний стан встановлення (сервіс пише, Activity читає). */
object InstallState {
    data class Snapshot(
        val running: Boolean = false,
        val phase: String = "",
        val percent: Int = 0,
        val log: String = "",
        val finished: Boolean = false,
        val error: String? = null,
        val archives: List<Arc> = emptyList(),
        val plan: Catalog.Plan? = null,
    )

    data class Arc(val name: String, val percent: Int = 0)

    @Volatile
    var snap = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l); l(snap) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun log(line: String) {
        logLines.addLast(line)
        while (logLines.size > 2000) logLines.removeFirst()
    }

    @Synchronized
    fun update(
        running: Boolean = snap.running, phase: String = snap.phase, percent: Int = snap.percent,
        finished: Boolean = snap.finished, error: String? = snap.error,
        archives: List<Arc> = snap.archives, plan: Catalog.Plan? = snap.plan,
    ) {
        snap = Snapshot(running, phase, percent, logLines.joinToString("\n"), finished, error, archives, plan)
        val s = snap
        main.post { listeners.forEach { it(s) } }
    }

    @Synchronized
    fun setArchives(names: List<String>) = update(archives = names.map { Arc(it) })

    @Synchronized
    fun setArcPercent(i: Int, pct: Int) {
        val cur = snap.archives
        if (i !in cur.indices || cur[i].percent == pct) return
        update(archives = cur.mapIndexed { k, a -> if (k == i) a.copy(percent = pct) else a })
    }

    @Volatile private var choiceFuture: java.util.concurrent.CompletableFuture<Catalog.Choice>? = null

    /** Показує користувачу вибір гри й мов і чекає відповіді (викликається з потоку встановлення). */
    fun awaitChoice(plan: Catalog.Plan, cancelled: () -> Boolean): Catalog.Choice {
        val f = java.util.concurrent.CompletableFuture<Catalog.Choice>()
        choiceFuture = f
        update(phase = "Оберіть гру й мови…", plan = plan)
        try {
            while (true) {
                if (cancelled()) throw InterruptedException()
                try { return f.get(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { }
            }
        } finally {
            choiceFuture = null
            update(plan = null)
        }
    }

    fun submitChoice(c: Catalog.Choice) { choiceFuture?.complete(c) }

    @Synchronized
    fun reset() { logLines.clear(); choiceFuture = null; snap = Snapshot() }
}
