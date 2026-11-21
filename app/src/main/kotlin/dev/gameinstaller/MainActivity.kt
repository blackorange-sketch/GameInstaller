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
import dev.gameinstaller.inno.InnoFiles
import dev.gameinstaller.iso.IsoImage
import dev.gameinstaller.iso.IsoSet
import dev.gameinstaller.inno.InnoHeader
import dev.gameinstaller.inno.ScriptScan
import dev.gameinstaller.install.InstallService
import dev.gameinstaller.install.SourceFs
import dev.gameinstaller.install.FolderFs
import dev.gameinstaller.install.IsoFs
import dev.gameinstaller.install.findInstallerExe
import dev.gameinstaller.inno.ExternalCopier
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
    private lateinit var overallText: TextView
    private lateinit var arcBox: LinearLayout
    private val arcViews = mutableListOf<Pair<TextView, ProgressBar>>()
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var scan: ScriptScan.Info? = null
    private var srcDir: File? = null
    private var setupRel: String? = null
    private var innoFileCount = 0
    private var isoList = listOf<File>()
    private var isoInner = ""
    private var jobsPaths = listOf<String>()
    private var jobsPwds = listOf<String>()
    private var destParent: String? = null

    private val pickSrc = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) PathUtil.treeUriToPath(uri)?.let { analyzeSource(File(it)) } ?: toast("Не вдалося визначити шлях")
    }
    private val pickDest = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) PathUtil.treeUriToPath(uri)?.let { destParent = it; refresh() } ?: toast("Не вдалося визначити шлях")
    }
    private val pickIso = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val paths = uris.mapNotNull { PathUtil.docUriToPath(it) }
            if (paths.size != uris.size) toast("Не вдалося визначити шлях до образу. Оберіть файл із пам'яті пристрою (Завантаження, SD)")
            else analyzeSource(null, paths.map { File(it) })
        }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val listener: (InstallState.Snapshot) -> Unit = { s ->
        phase.text = s.phase
        progress.progress = s.percent
        overallText.text = if (s.running || s.finished) "Загалом: ${s.percent}%" else ""
        if (arcViews.size != s.archives.size) {
            arcBox.removeAllViews(); arcViews.clear()
            s.archives.forEach { a ->
                val t = TextView(this).apply { textSize = 12f }
                val b = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
                arcBox.addView(t); arcBox.addView(b)
                arcViews.add(t to b)
            }
        }
        s.archives.forEachIndexed { k, a ->
            arcViews[k].first.text = "${a.name}: ${a.percent}%"
            arcViews[k].second.progress = a.percent
        }
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
        root.addView(label("1. Папка з інсталятором (де лежить Setup.exe або start.exe)"))
        root.addView(button("Обрати папку інсталятора") { ensureAccessThen { pickSrc.launch(null) } })
        root.addView(button("Або: обрати образ(и) ISO (можна кілька дисків)") { ensureAccessThen { pickIso.launch(arrayOf("*/*")) } })
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

        overallText = TextView(this).apply { textSize = 14f }
        root.addView(overallText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        arcBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(arcBox)
        phase = TextView(this)
        root.addView(phase)
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, (240 * resources.displayMetrics.density).toInt()))

        setContentView(ScrollView(this).apply { addView(root) })
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
        installBtn.isEnabled = scan != null && (jobsPaths.isNotEmpty() || innoFileCount > 0) && d != null &&
            nameEdit.text.isNotBlank() && !InstallState.snap.running
    }

    private class Analysis(
        val msg: String, val info: ScriptScan.Info, val paths: List<String>,
        val pwds: List<String>, val setupRel: String, val innoCount: Int,
        val isos: List<File> = emptyList(), val inner: String = "",
    )

    private fun isInno(b: ByteArray) = runCatching { InnoHeader.load(b); true }.getOrDefault(false)

    /** Спільний аналіз для папки й образів: fs уже вказує на корінь, де лежить інсталятор (або над ним). */
    private fun analyzeFs(fs: SourceFs, where: String, isoFiles: List<File>, inner: String): Analysis {
        val setupRel = findInstallerExe(fs, ::isInno) ?: throw IllegalStateException(
            "$where: не знайдено інсталятор Inno Setup (Setup.exe, start.exe або інший .exe у корені). Файли: " +
                fs.find("", "*", false).take(10).joinToString())
        val h = InnoHeader.load(fs.readBytes(setupRel))
        val info = ScriptScan.scan(h.setupId, h.header)
        val setup = runCatching { InnoFiles.parse(h) }.getOrNull()
        val inno = setup?.appFiles().orEmpty()
        val ext = setup?.let { ExternalCopier.plan(it) }.orEmpty()
        require(info.archives.isNotEmpty() || inno.isNotEmpty() || ext.isNotEmpty()) {
            "У цьому інсталяторі ($setupRel) немає ні архівів FreeArc, ні файлів, які можна розпакувати."
        }
        val missing = info.archives.filter { !fs.exists(it.file) }
        require(missing.isEmpty()) { "Бракує файлів: " + missing.joinToString { it.file } + if (isoFiles.isNotEmpty()) ". Додайте інший образ?" else "" }
        val msg = buildString {
            appendLine(info.title)
            appendLine("Інсталятор: $setupRel")
            if (isoFiles.isNotEmpty()) appendLine("Образів: ${isoFiles.size} (" + isoFiles.joinToString { it.name } + "); архіви тимчасово копіюються на пристрій")
            if (info.archives.isNotEmpty()) {
                appendLine("Архівів: ${info.archives.size}, разом ${PathUtil.human(info.archives.sumOf { fs.size(it.file) })}")
                info.archives.forEach { appendLine("  ${it.file}  ${PathUtil.human(fs.size(it.file))}") }
            }
            if (inno.isNotEmpty()) appendLine("Файлів у контейнері інсталятора: ${inno.size}")
            if (ext.isNotEmpty()) appendLine("Додаткових файлів з диска: ${ext.size} записів")
            if (info.tools.any { it == "srep" || it == "precomp" })
                append("Примітка: скрипт згадує srep/precomp; дрібні додаткові архіви з ними пропускаються.")
        }
        return Analysis(msg, info, info.archives.map { it.file }, info.archives.map { it.password },
            setupRel, inno.size + ext.size, isoFiles, inner)
    }

    private fun analyze(dir: File) = analyzeFs(FolderFs(dir), "У папці", emptyList(), "")

    private fun analyzeIso(isoFiles: List<File>): Analysis {
        val set = IsoSet(isoFiles.map { IsoImage(it) })
        // інсталятор шукаємо в корені образів, а якщо його там немає, у підпапках першого рівня
        val roots = listOf("") + set.entries.values.map { it.dir.substringBefore('/') }.filter { it.isNotEmpty() }.distinct()
        var firstErr: Exception? = null
        for (r in roots) {
            try { return analyzeFs(IsoFs(set, r), "В образах", isoFiles, r) } catch (e: Exception) { if (firstErr == null) firstErr = e }
        }
        val udf = set.images.mapNotNull { it.udfNote }.firstOrNull()?.let { ". UDF: $it" } ?: ""
        throw IllegalStateException((firstErr?.message ?: "порожні образи") + udf)
    }

    private fun analyzeSource(dir: File?, isoFiles: List<File> = emptyList()) {
        srcInfo.text = "Аналізую…"
        thread {
            val r = try {
                if (isoFiles.isNotEmpty()) analyzeIso(isoFiles) else analyze(dir!!)
            } catch (e: Exception) {
                runOnUiThread { srcInfo.text = "Помилка: ${e.message}"; scan = null; jobsPaths = emptyList(); innoFileCount = 0; refresh() }
                return@thread
            }
            runOnUiThread {
                srcInfo.text = r.msg
                scan = r.info; srcDir = dir; setupRel = r.setupRel; isoList = r.isos; isoInner = r.inner; jobsPaths = r.paths; jobsPwds = r.pwds; innoFileCount = r.innoCount
                if (nameEdit.text.isBlank()) nameEdit.setText(PathUtil.sanitizeDirName(r.info.defaultDirName))
                refresh()
            }
        }
    }

    private fun startInstall() {
        val d = currentDest() ?: return
        InstallService.start(this, jobsPaths, jobsPwds, d.path, setupRel, srcDir?.path,
            isoList.map { it.path }.ifEmpty { null }, isoInner)
    }
}
