plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.rag.MainKt")
}

// Relative paths in the CLI (rag/corpus, rag/index) resolve against the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in` // the `chat` REPL reads stdin
}

dependencies {
    api(project(":rag:core"))

    testImplementation(testFixtures(project(":rag:core")))
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
