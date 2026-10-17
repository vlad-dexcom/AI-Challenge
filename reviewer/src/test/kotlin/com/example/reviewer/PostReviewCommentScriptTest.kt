package com.example.reviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `scripts/post-review-comment.sh` against a stub `gh`: update the bot's comment when there is one, post a new one otherwise. */
class PostReviewCommentScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private val script = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "scripts/post-review-comment.sh") }.first { it.isFile }

    /** Stub: a lookup (`--jq`) prints [existingIds]; every other call is appended to the log. */
    private fun run(existingIds: String, review: String = "<!-- ai-review -->\n## AI review\n"): Pair<String, String> {
        val bin = tmp.newFolder("bin")
        val log = File(tmp.root, "calls.log")
        File(bin, "gh").apply {
            writeText("#!/usr/bin/env bash\nif [[ \"\$*\" == *--jq* ]]; then printf '%s' \"\$STUB_IDS\"; else echo \"\$*\" >> \"\$STUB_LOG\"; fi\n")
            setExecutable(true)
        }
        val file = File(tmp.root, "review.md").apply { writeText(review) }
        val p = try {
            ProcessBuilder("bash", script.path, file.path).redirectErrorStream(true).apply {
                environment()["PATH"] = bin.path + File.pathSeparator + System.getenv("PATH")
                environment()["REPO"] = "o/r"; environment()["PR_NUMBER"] = "7"
                environment()["STUB_IDS"] = existingIds; environment()["STUB_LOG"] = log.path
            }.start()
        } catch (e: java.io.IOException) { assumeTrue("bash not available", false); throw e }
        val out = p.inputStream.readBytes().decodeToString()
        assertEquals(out, 0, p.waitFor())
        return out to (if (log.isFile) log.readText() else "")
    }

    @Test fun postsANewCommentWhenThereIsNone() {
        val (out, calls) = run(existingIds = "")
        assertTrue(out, "Posted a new review comment." in out)
        assertTrue(calls, "--method POST repos/o/r/issues/7/comments" in calls && "body=@" in calls)
    }

    @Test fun updatesTheExistingCommentInsteadOfPostingAnother() {
        val (out, calls) = run(existingIds = "111\n222")
        assertTrue(out, "Updated review comment 111." in out)
        assertTrue(calls, "--method PATCH repos/o/r/issues/comments/111" in calls)
        assertTrue(calls, "POST" !in calls)
    }

    @Test fun anEmptyReviewPostsNothing() {
        val (out, calls) = run(existingIds = "", review = "")
        assertTrue(out, "No review to post" in out)
        assertEquals("", calls)
    }
}
