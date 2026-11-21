package dev.gameinstaller.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/** Foreground-сервіс, щоб довга розпаковка не переривалась, коли екран згас. */
class InstallService : Service() {
    private var installer: Installer? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            installer?.cancel()
            return START_NOT_STICKY
        }
        val paths = intent?.getStringArrayExtra(EXTRA_ARCHIVES) ?: return START_NOT_STICKY
        val pwds = intent?.getStringArrayExtra(EXTRA_PASSWORDS) ?: return START_NOT_STICKY
        val dest = intent?.getStringExtra(EXTRA_DEST) ?: return START_NOT_STICKY
        val setupRel = intent.getStringExtra(EXTRA_SETUP)
        val isos = intent.getStringArrayExtra(EXTRA_ISOS)
        val fs: SourceFs = if (isos != null && isos.isNotEmpty())
            IsoFs(dev.gameinstaller.iso.IsoSet(isos.map { dev.gameinstaller.iso.IsoImage(File(it)) }), intent.getStringExtra(EXTRA_INNER) ?: "")
        else FolderFs(File(intent.getStringExtra(EXTRA_SRC) ?: return START_NOT_STICKY))
        if (installer != null) return START_NOT_STICKY

        startForeground(NOTIF_ID, notification("Встановлення…", 0))
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gameinstaller:install").apply { acquire(6 * 60 * 60 * 1000L) }

        val inst = Installer(this)
        installer = inst
        val listener: (InstallState.Snapshot) -> Unit = { s ->
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, notification(s.phase.ifEmpty { "Встановлення…" }, s.percent))
        }
        InstallState.addListener(listener)
        thread(name = "installer") {
            inst.install(paths.indices.map { ArchiveJob(paths[it], pwds[it]) }, File(dest), setupRel, fs)
            InstallState.removeListener(listener)
            installer = null
            wake?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val s = InstallState.snap
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID + 1, notification(if (s.error == null) "Встановлення завершено" else "Помилка: ${s.error}", if (s.error == null) 100 else -1, ongoing = false))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, percent: Int, ongoing: Boolean = true): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Встановлення ігор", NotificationManager.IMPORTANCE_LOW))
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GameInstaller")
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (percent >= 0 && ongoing) b.setProgress(100, percent, false)
        return b.build()
    }

    companion object {
        const val EXTRA_ARCHIVES = "archives"
        const val EXTRA_PASSWORDS = "passwords"
        const val EXTRA_DEST = "dest"
        const val EXTRA_SETUP = "setup"
        const val EXTRA_SRC = "src"
        const val EXTRA_ISOS = "isos"
        const val EXTRA_INNER = "inner"
        const val ACTION_CANCEL = "dev.gameinstaller.CANCEL"
        private const val CHANNEL = "install"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, archives: List<String>, passwords: List<String>, dest: String, setupRel: String?, srcDir: String?, isos: List<String>? = null, inner: String = "") {
            val i = Intent(ctx, InstallService::class.java)
                .putExtra(EXTRA_ARCHIVES, archives.toTypedArray())
                .putExtra(EXTRA_PASSWORDS, passwords.toTypedArray())
                .putExtra(EXTRA_DEST, dest)
                .putExtra(EXTRA_SETUP, setupRel)
                .putExtra(EXTRA_SRC, srcDir)
                .putExtra(EXTRA_ISOS, isos?.toTypedArray())
                .putExtra(EXTRA_INNER, inner)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, InstallService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
