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
    )

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
        while (logLines.size > 150) logLines.removeFirst()
    }

    @Synchronized
    fun update(
        running: Boolean = snap.running, phase: String = snap.phase, percent: Int = snap.percent,
        finished: Boolean = snap.finished, error: String? = snap.error,
    ) {
        snap = Snapshot(running, phase, percent, logLines.joinToString("\n"), finished, error)
        val s = snap
        main.post { listeners.forEach { it(s) } }
    }

    @Synchronized
    fun reset() { logLines.clear(); snap = Snapshot() }
}
