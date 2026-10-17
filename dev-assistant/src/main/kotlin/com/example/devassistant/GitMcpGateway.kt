package com.example.devassistant

import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpServerInfo
import com.example.geminichat.mcp.McpToolCallException
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import com.example.geminichat.mcp.McpToolParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs `git <args>` and returns its stdout; throws [IllegalStateException] with git's message on failure. */
fun interface GitRunner {
    fun run(args: List<String>): String
}

/** Real git in [repoRoot]: arguments are passed as a list (no shell), output is capped and the call times out. */
class ProcessGitRunner(private val repoRoot: File, private val timeoutSeconds: Long = 10) : GitRunner {
    override fun run(args: List<String>): String {
        val process = ProcessBuilder(listOf("git", "-C", repoRoot.path, "--no-pager") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git ${args.first()} timed out after ${timeoutSeconds}s")
        }
        check(process.exitValue() == 0) { output.trim().ifBlank { "git ${args.first()} failed" } }
        return output
    }
}

/**
 * Day 31: read-only MCP server for the project's git repository, exposed through the same [McpGateway]
 * contract as the app's other tool servers (compare `LocalWorkoutMcpGateway`): no network, the "server" is
 * in-process and every tool shells out to `git` through a [GitRunner]. Tools never modify the repository.
 *
 * Tools: `git_branch`, `git_status`, `git_diff_stat`, `list_files` (Day 31) and `git_changed_files`, `git_diff` (Day 32:
 * what a pull request changes, relative to the merge base with [base]).
 */
class GitMcpGateway(
    private val git: GitRunner,
    private val maxLines: Int = 60,
    /** `git_diff` returns whole patches, so it has its own, larger cap. */
    private val maxDiffLines: Int = 3000,
) : McpGateway {

    override suspend fun connect(serverUrl: String) =
        McpServerInfo(name = "git-readonly-local", version = "1.0.0", capabilities = listOf("tools"), instructions = null)

    override suspend fun listTools(): List<McpToolInfo> = listOf(
        tool(BRANCH, "Current git branch", "Returns the current git branch (or the detached commit) and the last commit subject."),
        tool(STATUS, "Working tree status", "Lists changed and untracked files of the working tree with their short status."),
        tool(
            DIFF_STAT, "Diff statistics",
            "Summarises which files changed and by how many lines, against HEAD or another ref.",
            McpToolParam("base", "string", "Ref to compare with (default HEAD), e.g. main.", false),
        ),
        tool(
            LIST_FILES, "List tracked files",
            "Lists files tracked by git, optionally only below a directory prefix such as docs or agent/src.",
            McpToolParam("path", "string", "Directory prefix inside the repository (optional).", false),
        ),
        tool(
            CHANGED_FILES, "Files changed since the merge base",
            "Lists files changed between the merge base of base and head and head, as `<status>\\t<path>` lines (A added, M modified, D deleted).",
            McpToolParam("base", "string", "Base ref, e.g. origin/main.", true),
            McpToolParam("head", "string", "Head ref (default HEAD).", false),
        ),
        tool(
            DIFF, "Unified diff of one file",
            "Returns the unified diff of one file between the merge base of base and head and head.",
            McpToolParam("base", "string", "Base ref, e.g. origin/main.", true),
            McpToolParam("path", "string", "File path inside the repository.", true),
            McpToolParam("head", "string", "Head ref (default HEAD).", false),
        ),
    )

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolCallResult = try {
        val text = when (name) {
            BRANCH -> branch()
            STATUS -> limited(git.run(listOf("status", "--short", "--branch")))
            DIFF_STAT -> limited(git.run(listOf("diff", "--stat", ref(arguments["base"]?.toString() ?: "HEAD"), "--")).ifBlank { "No changes." })
            LIST_FILES -> listFiles(arguments["path"]?.toString())
            CHANGED_FILES -> limited(git.run(listOf("diff", "--name-status", "--no-renames", range(arguments), "--")).ifBlank { "No changes." })
            DIFF -> limited(
                git.run(listOf("diff", "--no-renames", "--unified=3", range(arguments), "--", safePath(arguments["path"]?.toString(), required = true))),
                maxDiffLines,
            ).ifBlank { "No changes." }
            else -> throw McpToolCallException("Unknown tool '$name'")
        }
        McpToolCallResult(text.trim(), isError = false)
    } catch (e: McpToolCallException) {
        throw e
    } catch (e: IllegalArgumentException) {
        McpToolCallResult(e.message ?: "Invalid arguments", isError = true)
    } catch (e: IllegalStateException) {
        McpToolCallResult(e.message ?: "git failed", isError = true)
    }

    override suspend fun close() = Unit

    private fun branch(): String {
        val name = git.run(listOf("rev-parse", "--abbrev-ref", "HEAD")).trim()
        val last = runCatching { git.run(listOf("log", "-1", "--format=%h %s")).trim() }.getOrDefault("")
        val head = if (name == "HEAD") "Detached HEAD" else "Current branch: $name"
        return if (last.isEmpty()) head else "$head\nLast commit: $last"
    }

    private fun listFiles(path: String?): String {
        val prefix = safePath(path, required = false)
        val args = buildList { add("ls-files"); if (prefix.isNotEmpty()) { add("--"); add(prefix) } }
        return limited(git.run(args).ifBlank { "No tracked files under '$prefix'." })
    }

    private fun limited(text: String, cap: Int = maxLines): String {
        val lines = text.trimEnd().lines()
        return if (lines.size <= cap) lines.joinToString("\n")
        else lines.take(cap).joinToString("\n") + "\n… (${lines.size - cap} more lines)"
    }

    private fun ref(value: String): String {
        require(SAFE_REF.matches(value) && !value.startsWith("-") && ".." !in value) { "Invalid ref '$value'" }
        return value
    }

    /** `base...head`: the changes on head since it diverged from base, which is what a pull request shows. */
    private fun range(arguments: Map<String, Any?>): String {
        val base = arguments["base"]?.toString()?.trim().orEmpty()
        require(base.isNotEmpty()) { "Argument 'base' is required" }
        return "${ref(base)}...${ref(arguments["head"]?.toString()?.trim()?.ifEmpty { null } ?: "HEAD")}"
    }

    private fun safePath(path: String?, required: Boolean): String {
        val value = path?.trim()?.trim('/').orEmpty()
        require(!required || value.isNotEmpty()) { "Argument 'path' is required" }
        require(value.isEmpty() || (SAFE_PATH.matches(value) && ".." !in value)) { "Invalid path '$path'" }
        return value
    }

    private fun tool(name: String, title: String, description: String, vararg params: McpToolParam) = McpToolInfo(
        name = name, title = title, description = description, parameters = params.toList(),
        rawInputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                params.forEach { p ->
                    putJsonObject(p.name) { put("type", p.type ?: "string"); put("description", p.description.orEmpty()) }
                }
            }
            put("required", JsonArray(params.filter { it.required }.map { JsonPrimitive(it.name) }))
        },
    )

    companion object {
        const val BRANCH = "git_branch"
        const val STATUS = "git_status"
        const val DIFF_STAT = "git_diff_stat"
        const val LIST_FILES = "list_files"
        const val CHANGED_FILES = "git_changed_files"
        const val DIFF = "git_diff"
        private val SAFE_REF = Regex("[A-Za-z0-9._/~^@{}-]{1,100}")
        private val SAFE_PATH = Regex("[A-Za-z0-9._/ -]{1,200}")
    }
}
