package com.example.aigateway

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Classic token bucket: [burst] tokens, refilled at [perMinute]/60 per second. */
class TokenBucket(private val burst: Int, private val perMinute: Int, private val clock: () -> Long = System::nanoTime) {
    private var tokens = burst.toDouble()
    private var last = clock()

    /** Seconds to wait before the next request is allowed, or 0 if one token was taken. */
    @Synchronized fun tryTake(): Long {
        val now = clock()
        tokens = minOf(burst.toDouble(), tokens + (now - last) / 1e9 * perMinute / 60.0)
        last = now
        if (tokens >= 1.0) { tokens -= 1.0; return 0 }
        return Math.ceil((1.0 - tokens) * 60.0 / perMinute).toLong().coerceAtLeast(1)
    }
}

class RateLimiter(private val burst: Int, private val perMinute: Int, private val clock: () -> Long = System::nanoTime) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    fun tryTake(key: String): Long = buckets.computeIfAbsent(key) { TokenBucket(burst, perMinute, clock) }.tryTake()
}

/** Caps simultaneous requests per device key. */
class PerKeyGate(private val max: Int) {
    private val active = ConcurrentHashMap<String, AtomicInteger>()
    fun tryEnter(key: String): Boolean {
        val c = active.computeIfAbsent(key) { AtomicInteger() }
        if (c.incrementAndGet() > max) { c.decrementAndGet(); return false }
        return true
    }
    fun leave(key: String) { active[key]?.decrementAndGet() }
}

enum class Admission { ADMITTED, QUEUE_FULL, QUEUE_TIMEOUT }

/** Bounded generation slots: at most [slots] run, at most [queueSize] wait, each for at most [timeoutMs]. */
class GenerationGate(slots: Int, private val queueSize: Int, private val timeoutMs: Long) {
    private val semaphore = Semaphore(slots)
    private val waiting = AtomicInteger()
    private val running = AtomicInteger()
    val queued: Int get() = waiting.get()
    val active: Int get() = running.get()

    suspend fun enter(): Admission {
        if (!semaphore.tryAcquire()) {
            if (waiting.incrementAndGet() > queueSize) { waiting.decrementAndGet(); return Admission.QUEUE_FULL }
            try {
                withTimeoutOrNull(timeoutMs) { semaphore.acquire() } ?: return Admission.QUEUE_TIMEOUT
            } finally {
                waiting.decrementAndGet()
            }
        }
        running.incrementAndGet()
        return Admission.ADMITTED
    }

    fun leave() { running.decrementAndGet(); semaphore.release() }
}

class Stats {
    private val started = System.currentTimeMillis()
    val requests = AtomicLong()
    val completed = AtomicLong()
    val upstreamErrors = AtomicLong()
    val clientDisconnects = AtomicLong()
    val completionTokens = AtomicLong()
    val promptTokens = AtomicLong()
    private val evalMillis = AtomicLong()
    private val queueWaitMillis = AtomicLong()
    private val rejected = ConcurrentHashMap<String, AtomicLong>()

    fun reject(reason: String) { rejected.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet() }
    fun generation(prompt: Int, completion: Int, evalMs: Long) {
        promptTokens.addAndGet(prompt.toLong()); completionTokens.addAndGet(completion.toLong()); evalMillis.addAndGet(evalMs)
    }
    fun queueWait(ms: Long) { queueWaitMillis.addAndGet(ms) }

    fun snapshot(active: Int, queued: Int): Map<String, Any> = mapOf(
        "uptimeSeconds" to (System.currentTimeMillis() - started) / 1000,
        "requests" to requests.get(),
        "completed" to completed.get(),
        "rejected" to rejected.toSortedMap().mapValues { it.value.get() },
        "upstreamErrors" to upstreamErrors.get(),
        "clientDisconnects" to clientDisconnects.get(),
        "active" to active,
        "queued" to queued,
        "promptTokens" to promptTokens.get(),
        "completionTokens" to completionTokens.get(),
        "avgTokensPerSecond" to evalMillis.get().let { if (it > 0) completionTokens.get() * 1000.0 / it else 0.0 },
        "avgQueueWaitMs" to completed.get().let { if (it > 0) queueWaitMillis.get().toDouble() / it else 0.0 },
    )
}

/** Cheap, deliberately conservative token estimate (3 chars ≈ 1 token) used to refuse over-long prompts. */
fun estimateTokens(messages: List<Pair<String, String>>): Int =
    messages.sumOf { (role, content) -> (role.length + content.length + 2) / 3 + 4 } + 3
