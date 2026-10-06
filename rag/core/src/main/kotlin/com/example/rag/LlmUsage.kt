package com.example.rag

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** LLM calls and wall-clock time spent in them. A cached call adds the latency recorded when it was first made. */
@OptIn(ExperimentalAtomicApi::class)
class LlmUsage {
    private val c = AtomicInt(0)
    private val ms = AtomicLong(0L)
    val calls: Int get() = c.load()
    val millis: Long get() = ms.load()
    fun add(latencyMs: Long) { c.addAndFetch(1); ms.addAndFetch(latencyMs) }
    fun reset() { c.store(0); ms.store(0L) }
}
