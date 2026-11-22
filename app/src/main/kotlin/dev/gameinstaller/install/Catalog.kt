package dev.gameinstaller.install

/** Файл усередині архіву (шлях із '/' як роздільником). */
class ArcEntry(val arc: Int, val path: String, val size: Long)

/**
 * Каталог вмісту архівів: які в них ігри (корені з *.SkuDef), які мови (папки Lang-xxx, xxxAudio),
 * і правила «що розпаковувати» для вибраних гри та мов. Чиста логіка без Android.
 */
object Catalog {
    class Game(val dir: String, val title: String, val regKey: String?, val size: Long)

    class Plan(
        val entries: List<ArcEntry>,
        val games: List<Game>,
        val ifaceLangs: List<String>,
        val voiceLangs: List<String>,
    ) {
        /** Чи є що питати в користувача. */
        val needsChoice get() = games.size > 1 || ifaceLangs.size > 1 || voiceLangs.size > 1
    }

    class Choice(val games: Set<String>, val iface: String?, val voice: String?)

    /** Правила для unarc (-fl): включити / виключити за префіксом шляху, без урахування регістру. */
    class Rules(val include: List<String>, val exclude: List<String>) {
        val isEmpty get() = include.isEmpty() && exclude.isEmpty()

        fun text(): String = (include.map { "+$it" } + exclude.map { "-$it" }).joinToString("\n") + "\n"

        private fun under(path: String, pre: String) =
            path.startsWith(pre) && (path.length == pre.length || path[pre.length] == '/')

        fun accepts(path: String): Boolean {
            val p = path.lowercase()
            if (exclude.any { under(p, it.lowercase()) }) return false
            if (include.isEmpty()) return true
            return include.any { under(p, it.lowercase()) }
        }
    }

    private val ifaceRe = Regex("^lang(?:uage)?[-_ ]([a-z]+)$", RegexOption.IGNORE_CASE)
    private val voiceRe = Regex("^([a-z]+)audio$", RegexOption.IGNORE_CASE)

    private fun segs(p: String) = p.split('/')

    /** Назва гри за відомими exe у корені. */
    private fun gameInfo(top: String, entries: List<ArcEntry>): Pair<String, String?> {
        val low = top.lowercase()
        fun has(exe: String) = entries.any { it.path.lowercase() == "$low/$exe" }
        return when {
            has("cnc3ep1.exe") -> "Kane's Wrath" to "Command and Conquer 3 Kanes Wrath"
            has("cnc3.exe") -> "Tiberium Wars" to "Command and Conquer 3"
            else -> "Папка $top" to null
        }
    }

    fun analyze(entries: List<ArcEntry>): Plan {
        val tops = LinkedHashMap<String, String>()
        val roots = LinkedHashSet<String>()
        for (e in entries) {
            val s = segs(e.path)
            if (s.size >= 2) tops.putIfAbsent(s[0].lowercase(), s[0])
            if (s.size == 2 && s[1].endsWith(".skudef", ignoreCase = true)) roots.add(s[0].lowercase())
        }
        val games = roots.map { r ->
            val top = tops[r] ?: r
            val (title, key) = gameInfo(top, entries)
            Game(top, title, key, entries.filter { it.path.lowercase().startsWith("$r/") }.sumOf { it.size })
        }
        val iface = sortedSetOf<String>()
        val voice = sortedSetOf<String>()
        for (e in entries) {
            val s = segs(e.path)
            if (s.size < 2) continue
            val top = s[0].lowercase()
            if (top in roots) {
                if (s.size >= 3) {
                    ifaceRe.matchEntire(s[1])?.let { iface.add(it.groupValues[1].lowercase()) }
                    voiceRe.matchEntire(s[1])?.let { voice.add(it.groupValues[1].lowercase()) }
                }
            } else {
                ifaceRe.matchEntire(s[0])?.let { iface.add(it.groupValues[1].lowercase()) }
            }
        }
        return Plan(entries, games, iface.toList(), voice.toList())
    }

    fun defaultChoice(p: Plan): Choice = Choice(
        p.games.map { it.dir.lowercase() }.toSet(),
        p.ifaceLangs.firstOrNull { it == "russian" } ?: p.ifaceLangs.firstOrNull(),
        p.voiceLangs.firstOrNull { it == "english" } ?: p.voiceLangs.firstOrNull(),
    )

    fun rules(p: Plan, c: Choice): Rules {
        if (p.games.isEmpty()) return Rules(emptyList(), emptyList())
        val selected = p.games.filter { it.dir.lowercase() in c.games }.map { it.dir.lowercase() }.toSet()
        val inc = LinkedHashSet<String>()
        val exc = LinkedHashSet<String>()
        val rootNames = p.games.associate { it.dir.lowercase() to it.dir }
        // другі рівні кожного кореня і всі верхні папки, у порядку появи
        val second = HashSet<Pair<String, String>>()   // (корінь у нижньому регістрі, реальна назва підпапки)
        val topDirs = LinkedHashMap<String, String>()
        for (e in p.entries) {
            val s = segs(e.path)
            if (s.size == 1) { inc.add(s[0]); continue }       // окремі файли в корені архіву
            topDirs.putIfAbsent(s[0].lowercase(), s[0])
            if (s.size >= 3) second.add(s[0].lowercase() to s[1])
        }
        for ((low, name) in topDirs) {
            if (low in rootNames) {
                if (low !in selected) continue
                inc.add(name)
                for ((r, sub) in second) {
                    if (r != low) continue
                    ifaceRe.matchEntire(sub)?.let { m ->
                        if (p.ifaceLangs.size > 1 && c.iface != null && m.groupValues[1].lowercase() != c.iface) exc.add("$name/$sub")
                    }
                    voiceRe.matchEntire(sub)?.let { m ->
                        if (p.voiceLangs.size > 1 && c.voice != null && m.groupValues[1].lowercase() != c.voice) exc.add("$name/$sub")
                    }
                }
            } else {
                // спільна накладка (Movies, lang-russian…): потрібна, якщо в якомусь вибраному корені є однойменна папка
                val needed = second.any { (r, sub) -> r in selected && sub.equals(name, ignoreCase = true) }
                if (!needed) continue
                inc.add(name)
                ifaceRe.matchEntire(name)?.let { m ->
                    if (p.ifaceLangs.size > 1 && c.iface != null && m.groupValues[1].lowercase() != c.iface) exc.add(name)
                }
            }
        }
        return Rules(inc.toList(), exc.toList())
    }

    /** Розмір вибраного (байт), загалом і по архівах. */
    fun selectedSizes(p: Plan, rules: Rules, arcCount: Int): LongArray {
        val out = LongArray(arcCount)
        for (e in p.entries) if (rules.accepts(e.path)) out[e.arc] += e.size
        return out
    }

    fun langTitle(key: String): String = when (key) {
        "russian" -> "Російська"
        "english" -> "Англійська"
        "german" -> "Німецька"
        "french" -> "Французька"
        "spanish" -> "Іспанська"
        "italian" -> "Італійська"
        "polish" -> "Польська"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    /** .reg для Winlator: те саме, що пише оригінальний інсталятор (Language і Locale) для вибраних ігор. */
    fun regText(p: Plan, c: Choice): String? {
        val lang = c.iface ?: return null
        val locale = when (lang) { "russian" -> "ru"; "english" -> "en"; "german" -> "de"; "french" -> "fr"; "spanish" -> "es"; "italian" -> "it"; "polish" -> "pl"; else -> null }
        val keys = p.games.filter { it.dir.lowercase() in c.games }.mapNotNull { it.regKey }.distinct()
        if (keys.isEmpty()) return null
        val sb = StringBuilder("Windows Registry Editor Version 5.00\r\n")
        for (base in listOf("SOFTWARE", "SOFTWARE\\Wow6432Node")) for (k in keys) {
            sb.append("\r\n[HKEY_LOCAL_MACHINE\\$base\\Electronic Arts\\Electronic Arts\\$k]\r\n")
            sb.append("\"Language\"=\"$lang\"\r\n")
            if (locale != null) {
                sb.append("\r\n[HKEY_LOCAL_MACHINE\\$base\\Electronic Arts\\$k]\r\n")
                sb.append("\"Locale\"=\"$locale\"\r\n")
            }
        }
        return sb.toString()
    }
}
