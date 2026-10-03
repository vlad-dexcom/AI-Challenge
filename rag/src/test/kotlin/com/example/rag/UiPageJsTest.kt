package com.example.rag

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the node regression test that renders the chat page's turnHtml (skipped when node is not installed). */
class UiPageJsTest {
    @Test fun turnHtmlRendersStructuredIdkAndErrorTurnsWithoutThrowing() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "rag/src/test/js").isDirectory }
        val p = try {
            ProcessBuilder("node", File(root, "rag/src/test/js/turn-html.test.js").path, File(root, "rag/src/main/resources/ui/index.html").path)
                .redirectErrorStream(true).start()
        } catch (e: java.io.IOException) { assumeTrue("node not available", false); return }
        val out = p.inputStream.readBytes().decodeToString()
        assertEquals(out, 0, p.waitFor())
    }
}
