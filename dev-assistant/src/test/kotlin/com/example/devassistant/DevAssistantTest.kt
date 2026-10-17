package com.example.devassistant

import com.example.core.llm.HashingEmbeddingClient
import com.example.geminichat.mcp.McpGateway
import com.example.geminichat.mcp.McpServerInfo
import com.example.geminichat.mcp.McpToolCallResult
import com.example.geminichat.mcp.McpToolInfo
import com.example.rag.Document
import com.example.rag.Retriever
import com.example.rag.ScriptedGenerator
import com.example.rag.hit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeGit(private val outputs: Map<String, String>) : GitRunner {
    val calls = mutableListOf<List<String>>()
    override fun run(args: List<String>): String {
        calls += args
        return outputs[args.joinToString(" ")] ?: error("fatal: unexpected git call ${args.joinToString(" ")}")
    }
}

class GitMcpGatewayTest {
    private val git = FakeGit(
        mapOf(
            "rev-parse --abbrev-ref HEAD" to "day-31-dev-assistant\n",
            "log -1 --format=%h %s" to "abc1234 Add help\n",
            "status --short --branch" to "## day-31-dev-assistant\n M README.md\n",
            "diff --stat HEAD --" to "",
            "ls-files -- docs" to "docs/a.md\ndocs/b.md\n",
        ),
    )
    private val gateway = GitMcpGateway(git)

    @Test fun `lists six read-only tools with schemas`() = runTest {
        val tools = gateway.listTools()
        assertEquals(listOf("git_branch", "git_status", "git_diff_stat", "list_files", "git_changed_files", "git_diff"), tools.map(McpToolInfo::name))
        assertTrue(tools.all { it.rawInputSchema != null })
    }

    @Test fun `git_branch returns branch and last commit`() = runTest {
        val r = gateway.callTool("git_branch", emptyMap())
        assertFalse(r.isError)
        assertEquals("Current branch: day-31-dev-assistant\nLast commit: abc1234 Add help", r.text)
    }

    @Test fun `empty diff is reported as no changes`() = runTest {
        assertEquals("No changes.", gateway.callTool("git_diff_stat", emptyMap()).text)
    }

    @Test fun `list_files passes the prefix after a double dash`() = runTest {
        assertEquals("docs/a.md\ndocs/b.md", gateway.callTool("list_files", mapOf("path" to "docs/")).text)
    }

    @Test fun `rejects option-like refs and path traversal without running git`() = runTest {
        val bad = gateway.callTool("git_diff_stat", mapOf("base" to "--output=/tmp/x"))
        assertTrue(bad.isError)
        assertTrue(gateway.callTool("list_files", mapOf("path" to "../secrets")).isError)
        assertTrue(git.calls.isEmpty())
    }

    @Test fun `pull request tools diff against the merge base`() = runTest {
        val pr = FakeGit(
            mapOf(
                "diff --name-status --no-renames origin/main...HEAD --" to "M\ta.kt\nA\tb.md\n",
                "diff --no-renames --unified=3 origin/main...feature/x -- docs/b.md" to "@@ -0,0 +1 @@\n+hi\n",
            ),
        )
        val g = GitMcpGateway(pr)
        assertEquals("M\ta.kt\nA\tb.md", g.callTool("git_changed_files", mapOf("base" to "origin/main")).text)
        assertEquals("@@ -0,0 +1 @@\n+hi", g.callTool("git_diff", mapOf("base" to "origin/main", "head" to "feature/x", "path" to "docs/b.md")).text)
    }

    @Test fun `pull request tools validate base, head and path`() = runTest {
        val pr = FakeGit(emptyMap())
        val g = GitMcpGateway(pr)
        assertTrue(g.callTool("git_changed_files", emptyMap()).isError)
        assertTrue(g.callTool("git_changed_files", mapOf("base" to "--output=x")).isError)
        assertTrue(g.callTool("git_changed_files", mapOf("base" to "a..b")).isError)
        assertTrue(g.callTool("git_diff", mapOf("base" to "main")).isError)
        assertTrue(g.callTool("git_diff", mapOf("base" to "main", "path" to "../etc/passwd")).isError)
        assertTrue(pr.calls.isEmpty())
    }

    @Test fun `git diff has its own larger line cap`() = runTest {
        val long = (1..500).joinToString("\n") { "+line $it" }
        val g = GitMcpGateway(GitRunner { long }, maxLines = 10, maxDiffLines = 400)
        assertTrue(g.callTool("git_diff", mapOf("base" to "main", "path" to "a.kt")).text.endsWith("… (100 more lines)"))
    }

    @Test fun `git failure becomes an error result`() = runTest {
        val r = GitMcpGateway(GitRunner { error("fatal: not a git repository") }).callTool("git_branch", emptyMap())
        assertTrue(r.isError)
        assertTrue("not a git repository" in r.text)
    }

    @Test fun `long output is truncated`() = runTest {
        val long = (1..100).joinToString("\n") { "f$it.kt" }
        val r = GitMcpGateway(maxLines = 10, git = GitRunner { long }).callTool("list_files", emptyMap())
        assertTrue(r.text.endsWith("… (90 more lines)"))
    }
}

class ProjectDocsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo() = tmp.newFolder("repo").also { root ->
        java.io.File(root, "README.md").writeText("# Readme\nHello\n")
        java.io.File(root, "AGENTS.md").writeText("# Agents\nRules\n")
        java.io.File(root, "docs").mkdirs()
        java.io.File(root, "docs/modules.md").writeText("# Modules\nTable\n")
        java.io.File(root, "docs/day10-old.md").writeText("# Day 10\nHistory\n")
    }

    @Test fun `loads readme, agents and docs but skips day notes by default`() {
        val docs = ProjectDocs.load(repo())
        assertEquals(listOf("README.md", "AGENTS.md", "docs/modules.md"), docs.map(Document::source))
        assertEquals("Modules", docs.last().title)
    }

    @Test fun `history flag adds day notes`() {
        assertTrue(ProjectDocs.load(repo(), includeHistory = true).any { it.source == "docs/day10-old.md" })
    }

    @Test fun `fingerprint changes with content`() {
        val a = ProjectDocs.load(repo())
        val b = a.map { it.copy(text = it.text + "x") }
        assertTrue(ProjectDocs.fingerprint(a) != ProjectDocs.fingerprint(b))
    }

    @Test fun `index is reused until docs change`() = kotlinx.coroutines.test.runTest {
        val docs = ProjectDocs.load(repo())
        val logs = mutableListOf<String>()
        val index = DocsIndex(tmp.newFolder("idx"), HashingEmbeddingClient())
        index.ensure(docs, log = logs::add)
        val firstRunLogs = logs.size
        index.ensure(docs, log = logs::add)
        assertEquals("second call must hit the cache", firstRunLogs, logs.size)
        index.ensure(docs.map { it.copy(text = it.text + "\nNew section") }, log = logs::add)
        assertTrue("changed docs must rebuild", logs.size > firstRunLogs)
    }
}

class HelpAssistantTest {
    private val docs = Retriever { listOf(hit("m#0", 0.9f, "docs/modules.md", "Modules", "The :agent module holds all agent logic: memory, profile, MCP.")) }
    private val git = GitMcpGateway(
        FakeGit(
            mapOf(
                "rev-parse --abbrev-ref HEAD" to "feature/x",
                "log -1 --format=%h %s" to "abc Subject",
                "status --short --branch" to "## feature/x\n M agent/Foo.kt",
                "diff --stat HEAD --" to " agent/Foo.kt | 2 +-",
            ),
        ),
    )

    private fun reply(answer: String, chunkId: String, quote: String) =
        """{"answerable":true,"answer":"$answer","sources":[{"file":"x","section":"","chunkId":"$chunkId"}],"quotes":[{"chunkId":"$chunkId","text":"$quote"}]}"""

    @Test fun `answers from docs with a verified citation`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success(reply("Agent logic lives in :agent [1].", "m#0", "holds all agent logic: memory, profile"))))
        val r = HelpAssistant(docs, gen, git).help("Where does agent logic live?").getOrThrow()
        assertFalse(r.structured.idk)
        assertEquals("docs/modules.md", r.structured.sources.single().file)
        assertEquals(listOf("git_branch", "git_status"), r.mcpCalls.map { it.tool })
    }

    @Test fun `git state is available to the model as a citable excerpt`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success(reply("You are on feature/x.", "mcp:git_branch", "Current branch: feature/x"))))
        val r = HelpAssistant(docs, gen, git).help("What branch am I on?").getOrThrow()
        assertFalse(r.structured.idk)
        assertEquals("git (MCP)", r.structured.sources.single().file)
        assertTrue("mcp:git_branch" in gen.calls.single().second)
    }

    @Test fun `diff stat is requested only for questions about changes`() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("""{"answerable":false,"answer":"","sources":[],"quotes":[]}""")))
        val r = HelpAssistant(docs, gen, git).help("Which files did I change?").getOrThrow()
        assertTrue(r.mcpCalls.any { it.tool == "git_diff_stat" })
    }

    @Test fun `fabricated quote falls back to a safe I don't know`() = runTest {
        val bad = Result.success(reply("Invented.", "m#0", "this sentence is not in the docs at all"))
        val gen = ScriptedGenerator(mutableListOf(bad, bad))
        val r = HelpAssistant(docs, gen, git).help("Where does agent logic live?").getOrThrow()
        assertTrue(r.structured.idk)
        assertEquals(2, gen.calls.size)
    }

    @Test fun `git failure does not block documentation answers`() = runTest {
        val broken = GitMcpGateway(GitRunner { error("fatal: not a git repository") })
        val gen = ScriptedGenerator(mutableListOf(Result.success(reply("In :agent [1].", "m#0", "holds all agent logic: memory, profile"))))
        val r = HelpAssistant(docs, gen, broken).help("Where does agent logic live?").getOrThrow()
        assertFalse(r.structured.idk)
        assertTrue(r.mcpCalls.all { it.isError })
    }

    @Test fun `blank question is rejected`() = runTest {
        assertTrue(HelpAssistant(docs, ScriptedGenerator(mutableListOf()), git).help("  ").isFailure)
    }
}
