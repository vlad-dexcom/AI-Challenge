package com.example.reviewer

/** One changed file of the pull request, with the patch rewritten so that every visible line carries its new-file line number. */
data class DiffFile(
    val path: String,
    /** `A`, `M` or `D` as reported by git. */
    val status: Char,
    val added: Int,
    val removed: Int,
    /** `L12+ code` for added lines, `L12  code` for context lines, `    - code` for removed ones; hunks separated by `@@` headers. */
    val annotated: String,
    /** New-file line numbers that appear in [annotated] (added and context lines): the only lines a finding may point at. */
    val visibleLines: Set<Int>,
    val addedLines: Set<Int>,
    val truncated: Boolean,
) {
    val hasContent: Boolean get() = annotated.isNotBlank()
}

object DiffParser {
    private val HUNK = Regex("""^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /** Parses the output of `git diff` for one file; stops after about [maxChars] characters of annotated text. */
    fun parse(path: String, status: Char, patch: String, maxChars: Int = Int.MAX_VALUE): DiffFile {
        val out = StringBuilder()
        val visible = mutableSetOf<Int>()
        val addedSet = mutableSetOf<Int>()
        var removed = 0
        var newLine = 0
        var inHunk = false
        var truncated = false
        // Lines the current hunk still has on each side (from its header). They tell where a hunk ends and let a context
        // line that lost its single leading space (an empty line, after whitespace stripping) be counted instead of skipped.
        var oldLeft = 0
        var newLeft = 0

        for (line in patch.removeSuffix("\n").lines()) {
            if (inHunk && oldLeft <= 0 && newLeft <= 0 && !line.startsWith("\\")) inHunk = false
            if (line.startsWith("@@")) { // content lines start with +, - or a space, never with @
                val m = HUNK.find(line)
                if (m != null) {
                    oldLeft = m.groupValues[1].ifEmpty { "1" }.toInt()
                    newLine = m.groupValues[2].toInt()
                    newLeft = m.groupValues[3].ifEmpty { "1" }.toInt()
                    inHunk = true
                }
                val header = "@@ new line $newLine @@"
                if (out.length + header.length > maxChars) { truncated = true; break }
                out.append(header).append('\n')
                continue
            }
            if (!inHunk || line.startsWith("\\")) continue
            val kind = if (line.isEmpty()) ' ' else line[0]
            val body = line.drop(1)
            val text = when (kind) {
                '+' -> "L$newLine+ $body"
                '-' -> "    - $body"
                else -> "L$newLine  $body"
            }
            if (out.length + text.length > maxChars) { truncated = true; break }
            out.append(text).append('\n')
            when (kind) {
                '+' -> { addedSet += newLine; visible += newLine; newLine++; newLeft-- }
                '-' -> { removed++; oldLeft-- }
                else -> { visible += newLine; newLine++; oldLeft--; newLeft-- }
            }
        }
        if (truncated) out.append("… (diff truncated)\n")
        return DiffFile(path, status, addedSet.size, removed, out.toString().trimEnd(), visible, addedSet, truncated)
    }

    /** `M\tpath` lines of `git diff --name-status`; anything else (notes, truncation markers) is ignored. */
    fun parseNameStatus(text: String): List<Pair<Char, String>> = text.lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        if (parts.size == 2 && parts[0].length == 1 && parts[0][0] in "AMDT") parts[0][0] to parts[1].trim() else null
    }
}

/** Which changed files are worth an AI review: source, scripts, build files and docs; not generated, binary or vendored files. */
object ReviewScope {
    private val SKIP_PATH = Regex("""(^|/)(build|node_modules|\.gradle)/|^rag/(index[^/]*|cache|sessions)/|^gradle/wrapper/""")
    private val SKIP_NAME = Regex("""(\.lock|package-lock\.json|\.(png|jpe?g|gif|webp|ico|pdf|jar|aar|apk|aab|keystore|jks|ttf|otf|woff2?|zip|bin))$""", RegexOption.IGNORE_CASE)

    fun isReviewable(path: String): Boolean = !SKIP_PATH.containsMatchIn(path) && !SKIP_NAME.containsMatchIn(path)

    /** Production code is reviewed first when the budget does not fit everything; docs and tests last. */
    fun priority(path: String): Int = when {
        path.endsWith(".kt") && "/src/test/" !in path && "/src/androidTest/" !in path -> 0
        path.endsWith(".kt") || path.endsWith(".kts") || path.endsWith(".ts") || path.endsWith(".js") || path.endsWith(".py") || path.endsWith(".sh") -> 1
        path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".xml") || path.endsWith(".toml") -> 2
        else -> 3
    }
}
