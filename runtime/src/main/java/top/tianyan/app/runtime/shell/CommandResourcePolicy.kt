package top.tianyan.app.runtime.shell

/** Admission hints for known foreground workloads, independent of file mutation/approval policy.
 * This is not a shell interpreter: aliases, expanded commands and arbitrary script contents remain unknown.
 */
object CommandResourcePolicy {
    fun needsMemorySlot(command: ShellCommand): Boolean = command.memoryHeavy || isHeavy(command.commandLine)

    fun isHeavy(commandLine: String): Boolean = isHeavy(commandLine, 0)

    private fun isHeavy(commandLine: String, depth: Int): Boolean {
        if (depth > 3 || commandLine.length > 65_536) return true
        return segments(commandLine).any { isHeavyInvocation(it, depth) }
    }

    private fun isHeavyInvocation(words: List<String>, depth: Int): Boolean {
        var index = 0
        while (index < words.size) {
            val word = words[index]
            if (assignment.matches(word) || word in controlPrefixes) { index++; continue }
            val executable = word.substringAfterLast('/').lowercase()
            if (executable in wrappers) {
                index++
                while (index < words.size && words[index].startsWith('-')) {
                    val option = words[index++]
                    if (option in wrapperValueOptions && index < words.size) index++
                }
                continue
            }
            val args = words.drop(index + 1)
            if (executable in shells) {
                val payload = args.indexOfFirst { it == "-c" || it == "-lc" || it == "-ec" }
                if (payload >= 0 && payload + 1 < args.size) return isHeavy(args[payload + 1], depth + 1)
                return args.firstOrNull { !it.startsWith('-') }?.substringAfterLast('/') in buildScripts
            }
            if (executable in alwaysHeavy) return !isOnlyInspection(args)
            if (executable in actionTools) {
                if (isOnlyInspection(args)) return false
                val action = args.firstOrNull { !it.startsWith('-') }
                return action !in actionTools.getValue(executable)
            }
            return executable in buildScripts
        }
        return false
    }

    private fun isOnlyInspection(args: List<String>): Boolean = args.isNotEmpty() && args.all { it in inspectionFlags }

    /** Preserve quoted arguments so `echo 'gradle build'` and search patterns do not reserve memory. */
    private fun segments(line: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var started = false
        var index = 0
        fun flushWord() { if (started) { words += word.toString(); word.setLength(0); started = false } }
        fun flushSegment() { flushWord(); if (words.isNotEmpty()) { result += words.toList(); words.clear() } }
        while (index < line.length) {
            val char = line[index++]
            if (char == '\\' && quote != '\'' && index < line.length) {
                val next = line[index++]
                if (next != '\n') { word.append(next); started = true }
            } else if (quote != null) {
                if (char == quote) quote = null else word.append(char)
            } else when {
                char == '\'' || char == '"' -> { quote = char; started = true }
                char == '#' && !started -> { while (index < line.length && line[index] != '\n') index++ }
                char in ";|&()\n\r" -> flushSegment()
                char.isWhitespace() -> flushWord()
                else -> { word.append(char); started = true }
            }
        }
        flushSegment()
        return result
    }

    private val assignment = Regex("[A-Za-z_][A-Za-z0-9_]*=.*", RegexOption.DOT_MATCHES_ALL)
    private val controlPrefixes = setOf("if", "then", "else", "elif", "do", "!", "{", "}")
    private val wrappers = setOf("env", "command", "exec", "nice", "time", "nohup", "rtk")
    private val wrapperValueOptions = setOf("-u", "--unset", "-C", "--chdir", "-n", "--adjustment", "-o", "--output")
    private val shells = setOf("sh", "bash", "dash", "zsh")
    private val inspectionFlags = setOf("--version", "-version", "-v", "-V", "-vV", "--help", "-help", "-h", "--status", "--stop")
    private val alwaysHeavy = setOf("gradle", "gradlew", "mvn", "mvnw", "mvnd", "java", "kotlinc", "rustc",
        "gcc", "g++", "clang", "clang++", "make", "ninja", "cmake", "jadx", "jadx-cli", "apktool", "d2j-dex2jar")
    /** Known light actions; other actions of these toolchains conservatively reserve a slot. */
    private val actionTools = mapOf(
        "flutter" to setOf("--version", "help", "doctor", "devices", "config"),
        "cargo" to setOf("help", "version", "fmt", "metadata", "locate-project", "search"),
        "go" to setOf("help", "version", "env", "list", "mod", "fmt", "clean"),
        "npm" to setOf("help", "version", "config", "list", "ls", "view", "info", "prefix", "root"),
        "pnpm" to setOf("help", "config", "list", "ls", "view", "info", "root", "store"),
        "yarn" to setOf("help", "config", "info", "list", "why", "cache"),
        "npx" to emptySet(),
    )
    private val buildScripts = setOf("tianyan-build.sh", "workshop-build-android.sh", "workshop-build-flutter.sh")
}
