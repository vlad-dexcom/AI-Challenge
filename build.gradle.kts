// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    // Since Kotlin 2.0 the Compose compiler is a separate Gradle plugin, versioned in lockstep
    // with the Kotlin Gradle plugin (needed for the MCP Kotlin SDK, which requires Kotlin 2.4+).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
