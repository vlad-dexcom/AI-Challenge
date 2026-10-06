// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Since Kotlin 2.0 the Compose compiler is a separate Gradle plugin, versioned in lockstep
    // with the Kotlin Gradle plugin (needed for the MCP Kotlin SDK, which requires Kotlin 2.4+).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// KMP-readiness guard: modules that are meant to move to Kotlin Multiplatform must not use JVM/Android
// APIs directly. Platform access goes through `platform` packages (e.g. core/common .../platform).
val kmpReadyModules = listOf("core/common", "core/llm", "rag/core", "agent")
val forbiddenApi = Regex("""^\s*import\s+(java|javax|android|androidx)\.|\b(java\.(io|util|time|nio|text|net|security)\.\w+|System\.(currentTimeMillis|getProperty|getenv|nanoTime))""")

tasks.register("checkKmpReadiness") {
    group = "verification"
    description = "Fails if KMP-ready modules use java.*/android.* APIs outside a `platform` package."
    val roots = kmpReadyModules.map { layout.projectDirectory.dir("$it/src/main") }
    doLast {
        val violations = roots.flatMap { root ->
            root.asFile.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("/platform/") }
                .flatMap { file ->
                    file.readLines().withIndex()
                        .filter { (_, line) -> !line.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } && forbiddenApi.containsMatchIn(line) }
                        .map { (i, line) -> "${file.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("JVM/Android APIs in KMP-ready modules (move behind a `platform` package):\n" + violations.joinToString("\n"))
        }
    }
}

subprojects {
    plugins.withId("base") { tasks.matching { it.name == "check" }.configureEach { dependsOn(rootProject.tasks.named("checkKmpReadiness")) } }
}
