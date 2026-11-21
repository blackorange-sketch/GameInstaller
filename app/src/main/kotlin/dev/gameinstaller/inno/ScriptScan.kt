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
        val u = Regex("""[ -~Ѐ-ӿ]{4,}""").findAll(String(hdr, Charsets.UTF_16LE)).map { it.value }.toList()
        val a = Regex("""[ -~]{6,}""").findAll(String(hdr, Charsets.ISO_8859_1)).map { it.value }.toList()
        val title = u.firstOrNull { "RePack" in it || "Setup" in it } ?: u.firstOrNull().orEmpty()
        val dirName = u.firstOrNull { s -> dirPrefixes.any { s.startsWith(it) } && '\\' in s }
            ?.substringAfterLast('\\')?.trim().orEmpty().ifEmpty { title }
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
