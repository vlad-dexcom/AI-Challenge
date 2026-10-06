package com.example.core.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// JVM implementation of what would be `expect fun defaultHttpEngine()` in a multiplatform build
// (OkHttp on Android/desktop, Darwin on iOS).
fun defaultHttpEngine(): HttpClientEngine = OkHttp.create()
