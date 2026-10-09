package com.example.webconsole

import com.example.core.llm.TextGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The two sides of a Local-vs-Cloud comparison are sent at the same time, so the server must handle them concurrently. */
class ParallelRequestsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: UiServer
    private val bothInside = CountDownLatch(2)
    private val metInParallel = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    @After fun tearDown() { if (::server.isInitialized) server.stop() }

    private fun post(path: String, body: String): Int {
        val c = URL("http://localhost:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode
    }

    @Test fun cloudAndLocalRequestsAreServedAtTheSameTime() {
        val generator = TextGenerator { _, _ ->
            bothInside.countDown()
            // Deadlocks (and times out) if the server handles requests one at a time.
            metInParallel += bothInside.await(5, TimeUnit.SECONDS)
            Result.success("answer")
        }
        val api = UiApi(
            tmp.newFolder("corpus"), tmp.newFolder("index"), "", controlFile = File(tmp.root, "c.json").path,
            generatorFactory = { generator }, localGeneratorFactory = { generator }, sessionsDir = tmp.newFolder("sessions"),
        )
        server = UiServer(api, 0).also { it.start() }
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf("gemini", "ollama").map { p ->
            pool.submit<Int> { post("/api/chat", """{"question":"hello","mode":"no_rag","provider":"$p"}""") }
        }.map { it.get(15, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(listOf(200, 200), results)
        assertEquals(listOf(true, true), metInParallel.toList())
    }
}
