package com.example.aigateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    @Test fun tokenBucketRefillsOverTime() {
        var now = 0L
        val b = TokenBucket(burst = 2, perMinute = 60, clock = { now })
        assertEquals(0, b.tryTake()); assertEquals(0, b.tryTake())
        assertEquals(1, b.tryTake())
        now += 1_000_000_000L
        assertEquals(0, b.tryTake())
        assertTrue(b.tryTake() >= 1)
    }

    @Test fun perKeyGateCountsAndReleases() {
        val g = PerKeyGate(1)
        assertTrue(g.tryEnter("a")); assertFalse(g.tryEnter("a")); assertTrue(g.tryEnter("b"))
        g.leave("a"); assertTrue(g.tryEnter("a"))
    }

    @Test fun generationGateAdmitsQueuesAndRejects() = runBlocking {
        val g = GenerationGate(slots = 1, queueSize = 0, timeoutMs = 50)
        assertEquals(Admission.ADMITTED, g.enter())
        assertEquals(Admission.QUEUE_FULL, g.enter())
        val q = GenerationGate(1, 1, 50)
        q.enter()
        assertEquals(Admission.QUEUE_TIMEOUT, q.enter())
        assertEquals(0, q.queued)
        q.leave()
        assertEquals(Admission.ADMITTED, q.enter())
    }

    @Test fun keyStoreMatchesOnlyTheRightKeyAndNeverStoresPlaintext() {
        val key = KeyHasher.newKey()
        val store = KeyStore(listOf(KeyEntry("phone", KeyHasher.sha256Hex(key)), KeyEntry("broken", "zz")))
        assertEquals("phone", store.authenticate(key))
        assertNull(store.authenticate("$key-x"))
        assertNull(store.authenticate(""))
        assertTrue(key.startsWith("sk-lan-") && key.length > 30)
    }

    @Test fun tokenEstimateIsConservative() {
        assertTrue(estimateTokens(listOf("user" to "a".repeat(300))) >= 100)
    }
}
