package dev.gameinstaller.inno

/** Витягує зі скрипту інсталятора архіви FreeArc, пароль, ім'я папки гри за замовчуванням. */
object ScriptScan {
    class Archive(val file: String, val password: String)

    class Info(
        val setupId: String,
        val title: String,
        val defaultDirName: String,
        val helpers: List<String>,
        val archives: List<Archive>,
        val isDoneApi: List<String>,
        val tools: List<String>,
    )

    private val unarc = Regex("""x\s+-o\+.*?-p"([^"]*)".*?"\{src\}\\([^"]+)"""")
    private val api = Regex("""IS[A-Za-z]+Extract|ISDone[A-Za-z]*|SrepInit|PrecompInit""")
    private val toolNames = listOf("srep", "precomp", "packjpg", "xtool", "unrar", "7z", "zstd", "reflate")
    private val dirPrefixes = listOf("{code:", "{pf}", "{pf32}", "{pf64}", "{sd}")

    fun scan(setupId: String, hdr: ByteArray): Info {
        val um = Regex("[ -~\u0400-\u04FF]{4,}").findAll(String(hdr, Charsets.UTF_16LE)).toList()
        val u = um.map { it.value }
        val a = Regex("[ -~]{6,}").findAll(String(hdr, Charsets.ISO_8859_1)).map { it.value }.toList()
        val title = u.firstOrNull { "RePack" in it || "Setup" in it } ?: u.firstOrNull().orEmpty()
        // Перед рядком Inno лежить u32 з довжиною в байтах; зайвий символ у збігу це байт довжини наступного рядка.
        fun exact(m: MatchResult): String {
            val off = m.range.first * 2
            if (off < 4) return m.value
            val n = (hdr[off - 4].toInt() and 255) or ((hdr[off - 3].toInt() and 255) shl 8)
            return if (n >= 2 && n < m.value.length * 2 && n % 2 == 0) m.value.take(n / 2) else m.value
        }
        val dirName = um.firstOrNull { m -> dirPrefixes.any { m.value.startsWith(it) } && m.value.contains('\\') }
            ?.let { exact(it).substringAfterLast('\\').trim() }.orEmpty().ifEmpty { title }
        val helpers = u.filter { it.startsWith("{tmp}\\") && it.lowercase().let { s -> listOf(".dll", ".exe", ".ini", ".inf").any(s::endsWith) } }.distinct().sorted()
        val archives = a.mapNotNull { s -> unarc.find(s)?.let { Archive(it.groupValues[2], it.groupValues[1]) } }
            .distinctBy { it.file }
        return Info(
            setupId, title, dirName, helpers, archives,
            a.filter { api.matches(it) }.distinct().sorted(),
            toolNames.filter { t -> a.any { t in it.lowercase() } },
        )
    }
}
