package dev.gameinstaller

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.gameinstaller.inno.InnoHeader
import dev.gameinstaller.inno.ScriptScan
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var out: TextView

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) analyze(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(32, 32, 32, 32); textSize = 14f }
        val btn = Button(this).apply {
            text = "Обрати Setup.exe"
            setOnClickListener { pick.launch(arrayOf("*/*")) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btn)
            addView(ScrollView(this@MainActivity).apply { addView(out) })
        })
        out.text = "Native: ${NativeBridge.version()}"
    }

    private fun analyze(uri: Uri) {
        out.text = "Читаю заголовок…"
        thread {
            val text = try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val h = InnoHeader.load(bytes)
                val i = ScriptScan.scan(h.setupId, h.header)
                buildString {
                    appendLine(i.setupId); appendLine(i.title); appendLine()
                    appendLine("Архіви (порядок розпаковки):")
                    i.archives.forEach { appendLine("  ${it.file}  (пароль ${it.password.length} симв.)") }
                    appendLine(); appendLine("API: " + i.isDoneApi.joinToString())
                    appendLine("Компресори: " + i.tools.joinToString())
                }
            } catch (e: Exception) {
                "Помилка: ${e.message}"
            }
            runOnUiThread { out.text = text }
        }
    }
}
