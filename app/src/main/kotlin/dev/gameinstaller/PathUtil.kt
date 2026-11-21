package dev.gameinstaller

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

object PathUtil {
    /** content://...tree/primary%3ADownload%2FGames -> /storage/emulated/0/Download/Games */
    fun treeUriToPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val vol = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val base = if (vol == "primary") Environment.getExternalStorageDirectory().path else "/storage/$vol"
        if (rel.isEmpty()) base else "$base/$rel"
    } catch (_: Exception) {
        null
    }

    fun sanitizeDirName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.').ifEmpty { "Game" }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f ГБ", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f МБ", bytes / 1048576.0)
        else -> String.format("%d КБ", bytes / 1024)
    }
}
