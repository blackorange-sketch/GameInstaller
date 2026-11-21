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
import dev.gameinstaller.install.findInstallerExes
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
    private val pickIsoExtract = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val paths = uris.mapNotNull { PathUtil.docUriToPath(it) }
        val d = currentDest()
        if (paths.size != uris.size) toast("Не вдалося визначити шлях до образу. Оберіть файл із пам'яті пристрою")
        else if (d == null) toast("Спочатку оберіть папку призначення й назву папки (крок 2)")
        else InstallService.extractIsos(this, paths, d.path)
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

        root.addView(button("Розпакувати образи ISO в папку (для запуску в Winlator)") {
            if (destParent == null || nameEdit.text.isBlank()) toast("Спочатку оберіть папку призначення й назву папки (крок 2)")
            else ensureAccessThen { pickIsoExtract.launch(arrayOf("*/*")) }
        })
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
        val isos: List<File> = emptyList(), val inner: String = "", val srcRoot: File? = null,
    )

    /** null якщо це інсталятор Inno, інакше причина відмови з підказкою про тип інсталятора. */
    private fun innoError(b: ByteArray): String? {
        try { InnoHeader.load(b); return null } catch (e: Throwable) {
            val l = String(b, Charsets.ISO_8859_1)
            val kind = when {
                "Nullsoft" in l -> "схоже на NSIS"
                "Inno Setup" in l -> "є напис Inno Setup, але розбір не вдався"
                "InstallShield" in l -> "схоже на InstallShield"
                "WiX" in l || "Windows Installer" in l -> "схоже на MSI/WiX"
                "7-Zip" in l || "7zS" in l -> "схоже на 7-Zip SFX"
                else -> "невідомий тип"
            }
            return "${e.javaClass.simpleName}: ${e.message} ($kind)"
        }
    }

    /** Аналіз одного інсталятора [setupRel] у fs (fs вказує на папку, де він лежить). */
    private fun analyzeFs(fs: SourceFs, setupRel: String, isoFiles: List<File>, inner: String, srcRoot: File?): Analysis {
        val h = InnoHeader.load(fs.readBytes(setupRel))
        val info = ScriptScan.scan(h.setupId, h.header)
        val setup = runCatching { InnoFiles.parse(h) }.getOrNull()
        val inno = setup?.appFiles().orEmpty()
        val ext = setup?.let { ExternalCopier.plan(it) }.orEmpty()
        require(info.archives.isNotEmpty() || inno.isNotEmpty() || ext.isNotEmpty()) {
            "У цьому інсталяторі ($setupRel) немає ні архівів FreeArc, ні файлів, які можна розпаковати."
        }
        val missing = info.archives.filter { !fs.exists(it.file) }
        require(missing.isEmpty()) { "Бракує файлів: " + missing.joinToString { it.file } + if (isoFiles.isNotEmpty()) ". Додайте інший образ?" else "" }
        val msg = buildString {
            appendLine(info.title)
            appendLine("Інсталятор: ${if (inner.isEmpty()) "" else "$inner/"}$setupRel")
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
            setupRel, inno.size + ext.size, isoFiles, inner, srcRoot)
    }

    /** Кандидат: де лежить інсталятор. [fs] кореня + шлях усередині. */
    private class Cand(val fs: SourceFs, val rel: String, val inner: String, val srcRoot: File?) {
        val label get() = (if (inner.isEmpty()) "" else "$inner/") + rel
    }

    /** Шукає інсталятори Inno в корені й підпапках першого рівня (там, де вони лежать, наприклад data\cnc3ke.exe). */
    private fun candidates(dirs: List<String>, fsFor: (String) -> SourceFs, rootFile: File?): Pair<List<Cand>, String> {
        val out = ArrayList<Cand>()
        val rep = StringBuilder()
        for (d in dirs) {
            val fs = fsFor(d)
            val r = findInstallerExes(fs, ::innoError)
            r.found.forEach { out.add(Cand(fs, it, d, rootFile?.let { f -> if (d.isEmpty()) f else File(f, d) })) }
            if (r.report.isNotEmpty()) rep.append(if (d.isEmpty()) "корінь: " else "$d: ").append(r.report)
        }
        return out to rep.toString()
    }

    private fun analyze(dir: File): List<Cand> {
        val subs = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
        val (c, rep) = candidates(listOf("") + subs, { d -> FolderFs(if (d.isEmpty()) dir else File(dir, d)) }, dir)
        if (c.isEmpty()) throw IllegalStateException("У папці інсталятор Inno Setup не знайдено. $rep")
        return c
    }

    private fun analyzeIso(isoFiles: List<File>): List<Cand> {
        val set = IsoSet(isoFiles.map { IsoImage(it) })
        val subs = set.entries.values.map { it.dir.substringBefore('/') }.filter { it.isNotEmpty() }.distinct().sorted()
        val (c, rep) = candidates(listOf("") + subs, { d -> IsoFs(set, d) }, null)
        val udf = set.images.mapNotNull { it.udfNote }.firstOrNull()?.let { ". UDF: $it" } ?: ""
        if (c.isEmpty()) throw IllegalStateException("В образах інсталятор Inno Setup не знайдено. $rep$udf")
        return c
    }

    private fun analyzeSource(dir: File?, isoFiles: List<File> = emptyList()) {
        srcInfo.text = "Аналізую…"
        thread {
            val cands = try {
                if (isoFiles.isNotEmpty()) analyzeIso(isoFiles) else analyze(dir!!)
            } catch (e: Exception) {
                runOnUiThread { srcInfo.text = "Помилка: ${e.message}"; scan = null; jobsPaths = emptyList(); innoFileCount = 0; refresh() }
                return@thread
            }
            runOnUiThread {
                if (cands.size == 1) applyCand(cands[0], dir, isoFiles)
                else android.app.AlertDialog.Builder(this)
                    .setTitle("Знайдено кілька інсталяторів")
                    .setItems(cands.map { it.label }.toTypedArray()) { _, i -> applyCand(cands[i], dir, isoFiles) }
                    .setOnCancelListener { srcInfo.text = "Не обрано" }
                    .show()
            }
        }
    }

    private fun applyCand(c: Cand, dir: File?, isoFiles: List<File>) {
        srcInfo.text = "Аналізую…"
        thread {
            val r = try { analyzeFs(c.fs, c.rel, isoFiles, c.inner, c.srcRoot) } catch (e: Exception) {
                runOnUiThread { srcInfo.text = "Помилка: ${e.message}"; scan = null; jobsPaths = emptyList(); innoFileCount = 0; refresh() }
                return@thread
            }
            runOnUiThread {
                srcInfo.text = r.msg
                scan = r.info; srcDir = r.srcRoot ?: dir; setupRel = r.setupRel; isoList = r.isos; isoInner = r.inner; jobsPaths = r.paths; jobsPwds = r.pwds; innoFileCount = r.innoCount
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
