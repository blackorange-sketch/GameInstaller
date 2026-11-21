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
        /** Рядки зі скрипту, схожі на пароль (найближчі до записів dataN.bin першими); перевіряються на справжньому архіві. */
        val pwCandidates: List<String> = emptyList(),
    )

    private val unarc = Regex("""x\s+-o\+.*?-p"([^"]*)".*?"\{src\}\\([^"]+)"""")
    private val srcFile = Regex(""""\{src\}\\([^"]+)"""")
    private val pwd = Regex("""-p"([^"]*)"""")
    private val srcOnly = Regex("""\{src\}\\(.+\.(?:bin|arc))""", RegexOption.IGNORE_CASE)
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
        val sc = a + u                            // рядки скрипту можуть бути і в ANSI, і в UTF-16
        var archives = sc.mapNotNull { s -> unarc.find(s)?.let { Archive(it.groupValues[2].replace('\\', '/'), it.groupValues[1]) } }
            .distinctBy { it.file }
        if (archives.isEmpty()) {
            // запасний варіант: команда без пароля або шляхи до архівів окремими рядками
            val cmd = sc.mapNotNull { s ->
                if (!Regex("""\bx\s+-o\+""").containsMatchIn(s)) null
                else srcFile.find(s)?.let { Archive(it.groupValues[1].replace('\\', '/'), pwd.find(s)?.groupValues?.get(1) ?: "") }
            }
            archives = (if (cmd.isNotEmpty()) cmd else
                sc.mapNotNull { s -> srcOnly.matchEntire(s)?.let { Archive(it.groupValues[1].replace('\\', '/'), "") } }).distinctBy { it.file }
        }
        // ANSI-інсталятори (Inno 5.x без "(u)"): рядки заголовка мають префікс довжини u32, 0-й це AppName, 10-й DefaultDirName
        var title2 = title
        var dirName2 = dirName
        if (!setupId.contains("(u)", ignoreCase = true)) {
            val strs = ansiStrings(hdr, 11)
            strs.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { title2 = it }
            strs.getOrNull(10)?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }?.let { dirName2 = it }
                ?: run { if (title2.isNotBlank()) dirName2 = title2 }
        }
        // кандидати пароля: друковані рядки без пробілів і службових символів, ближчі до першого {src}\*.bin йдуть першими
        val aM = Regex("[ -~]{6,}").findAll(String(hdr, Charsets.ISO_8859_1)).toList()
        val anchor = aM.firstOrNull { srcOnly.matchEntire(it.value) != null }?.range?.first ?: 0
        val cand = LinkedHashSet<String>()
        for (m in aM.sortedBy { Math.abs(it.range.first - anchor) }) {
            val v = m.value
            if (v.length !in 6..40 || v.any { it == '\\' || it == '{' || it == ' ' || it == '"' }) continue
            cand.add(v); if (v.length >= 7) cand.add(v.drop(1))   // перший символ міг бути байтом довжини
            if (cand.size > 4000) break
        }
        return Info(
            setupId, title2, dirName2, helpers, archives,
            a.filter { api.matches(it) }.distinct().sorted(),
            toolNames.filter { t -> a.any { t in it.lowercase() } },
            cand.toList(),
        )
    }

    /** Перші [n] рядків заголовка ANSI-інсталятора: u32 довжина + байти. */
    private fun ansiStrings(h: ByteArray, n: Int): List<String> {
        val out = ArrayList<String>()
        var p = 0
        repeat(n) {
            if (p + 4 > h.size) return out
            val len = (h[p].toInt() and 255) or ((h[p + 1].toInt() and 255) shl 8) or ((h[p + 2].toInt() and 255) shl 16) or ((h[p + 3].toInt() and 255) shl 24)
            if (len < 0 || p + 4 + len > h.size) return out
            out.add(String(h, p + 4, len, Charsets.ISO_8859_1))
            p += 4 + len
        }
        return out
    }
}
