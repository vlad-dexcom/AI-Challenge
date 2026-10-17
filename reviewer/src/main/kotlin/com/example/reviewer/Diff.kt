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
    private val HUNK = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** Parses the output of `git diff` for one file; stops after about [maxChars] characters of annotated text. */
    fun parse(path: String, status: Char, patch: String, maxChars: Int = Int.MAX_VALUE): DiffFile {
        val out = StringBuilder()
        val visible = mutableSetOf<Int>()
        val addedSet = mutableSetOf<Int>()
        var removed = 0
        var newLine = 0
        var inHunk = false
        var truncated = false

        for (line in patch.lines()) {
            if (line.startsWith("@@")) {
                val m = HUNK.find(line)
                if (m != null) { newLine = m.groupValues[1].toInt(); inHunk = true }
                val header = "@@ new line $newLine @@"
                if (out.length + header.length > maxChars) { truncated = true; break }
                out.append(header).append('\n')
                continue
            }
            if (!inHunk || line.isEmpty() || line.startsWith("\\")) continue
            val text = when (line[0]) {
                '+' -> "L$newLine+ ${line.drop(1)}"
                '-' -> "    - ${line.drop(1)}"
                else -> "L$newLine  ${line.drop(1)}"
            }
            if (out.length + text.length > maxChars) { truncated = true; break }
            out.append(text).append('\n')
            when (line[0]) {
                '+' -> { addedSet += newLine; visible += newLine; newLine++ }
                '-' -> removed++
                else -> { visible += newLine; newLine++ }
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
