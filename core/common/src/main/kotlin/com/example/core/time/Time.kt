package com.example.core.time

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Epoch milliseconds from the multiplatform clock (replaces `System.currentTimeMillis()`). */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Random id string (replaces `UUID.randomUUID().toString()`). */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

const val MILLIS_PER_DAY: Long = 86_400_000L
