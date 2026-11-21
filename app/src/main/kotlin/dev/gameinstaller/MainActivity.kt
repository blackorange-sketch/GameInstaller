package dev.gameinstaller

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.gameinstaller.inno.InnoHeader
import dev.gameinstaller.inno.ScriptScan
import dev.gameinstaller.install.InstallService
import dev.gameinstaller.install.InstallState
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var srcInfo: TextView
    private lateinit var destInfo: TextView
    private lateinit var nameEdit: EditText
    private lateinit var installBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var phase: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var scan: ScriptScan.Info? = null
    private var srcDir: File? = null
    private var jobsPaths = listOf<String>()
    private var jobsPwds = listOf<String>()
    private var destParent: String? = null

    private val pickSrc = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) PathUtil.treeUriToPath(uri)?.let { analyzeSource(File(it)) } ?: toast("Не вдалося визначити шлях")
    }
    private val pickDest = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) PathUtil.treeUriToPath(uri)?.let { destParent = it; refresh() } ?: toast("Не вдалося визначити шлях")
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val listener: (InstallState.Snapshot) -> Unit = { s ->
        phase.text = s.phase
        progress.progress = s.percent
        logView.text = s.log
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        cancelBtn.visibility = if (s.running) View.VISIBLE else View.GONE
        if (s.finished) phase.text = "Готово! Файли гри в: ${currentDest()?.path}"
        if (s.error != null && !s.running) phase.text = "${s.phase}: ${s.error}"
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, pad, 0, pad / 3) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

        root.addView(TextView(this).apply { text = "GameInstaller"; textSize = 22f })
        root.addView(label("1. Папка з інсталятором (де лежить Setup.exe і Setup-*.bin)"))
        root.addView(button("Обрати папку інсталятора") { ensureAccessThen { pickSrc.launch(null) } })
        srcInfo = TextView(this).apply { text = "Не обрано" }
        root.addView(srcInfo)

        root.addView(label("2. Куди встановити"))
        root.addView(button("Обрати папку призначення") { ensureAccessThen { pickDest.launch(null) } })
        destInfo = TextView(this).apply { text = "Не обрано" }
        root.addView(destInfo)
        root.addView(label("Назва папки гри"))
        nameEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(nameEdit)

        installBtn = button("Встановити") { startInstall() }.apply { isEnabled = false }
        root.addView(installBtn)
        cancelBtn = button("Скасувати") { InstallService.cancel(this) }.apply { visibility = View.GONE }
        root.addView(cancelBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        phase = TextView(this)
        root.addView(phase)
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() { super.onStart(); InstallState.addListener(listener) }
    override fun onStop() { super.onStop(); InstallState.removeListener(listener) }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    /** Для прямого доступу до файлів (unarc працює зі шляхами) потрібен «Доступ до всіх файлів». */
    private fun ensureAccessThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            toast("Увімкніть «Доступ до всіх файлів» для GameInstaller і поверніться")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else action()
    }

    private fun currentDest(): File? {
        val p = destParent ?: return null
        val n = PathUtil.sanitizeDirName(nameEdit.text.toString())
        return File(p, n)
    }

    private fun refresh() {
        val d = currentDest()
        destInfo.text = if (destParent == null) "Не обрано" else "Буде встановлено в: ${d?.path}"
        installBtn.isEnabled = scan != null && jobsPaths.isNotEmpty() && d != null &&
            nameEdit.text.isNotBlank() && !InstallState.snap.running
    }

    private fun analyzeSource(dir: File) {
        srcInfo.text = "Аналізую…"
        thread {
            var msg = ""
            var info: ScriptScan.Info? = null
            var paths = listOf<String>()
            var pwds = listOf<String>()
            try {
                val setup = dir.listFiles()?.firstOrNull { it.name.equals("Setup.exe", true) }
                    ?: throw IllegalStateException("У папці немає Setup.exe")
                val h = InnoHeader.load(setup.readBytes())
                val i = ScriptScan.scan(h.setupId, h.header)
                require(i.archives.isNotEmpty()) {
                    "Це Inno Setup, але без архівів FreeArc. Такі інсталятори поки не підтримуються."
                }
                val files = i.archives.map { File(dir, it.file) }
                val missing = files.filter { !it.exists() }
                require(missing.isEmpty()) { "Бракує файлів: " + missing.joinToString { it.name } }
                info = i
                paths = files.map { it.path }
                pwds = i.archives.map { it.password }
                msg = buildString {
                    appendLine(i.title)
                    appendLine("Архівів: ${files.size}, разом ${PathUtil.human(files.sumOf { it.length() })}")
                    files.forEach { appendLine("  ${it.name}  ${PathUtil.human(it.length())}") }
                    if (i.tools.any { it == "srep" || it == "precomp" })
                        append("Примітка: скрипт згадує srep/precomp; дрібні додаткові архіви з ними пропускаються.")
                }
            } catch (e: Exception) {
                runOnUiThread { srcInfo.text = "Помилка: ${e.message}"; scan = null; jobsPaths = emptyList(); refresh() }
                return@thread
            }
            runOnUiThread {
                srcInfo.text = msg
                scan = info; srcDir = dir; jobsPaths = paths; jobsPwds = pwds
                if (nameEdit.text.isBlank()) nameEdit.setText(PathUtil.sanitizeDirName(info!!.defaultDirName))
                refresh()
            }
        }
    }

    private fun startInstall() {
        val d = currentDest() ?: return
        InstallService.start(this, jobsPaths, jobsPwds, d.path)
    }
}
