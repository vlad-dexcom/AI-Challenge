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
    mainClass.set("com.example.reviewer.MainKt")
}

// Relative paths (README.md, docs/, rag/index-docs, rag/index-code) resolve against the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    api(project(":dev-assistant"))

    testImplementation(testFixtures(project(":rag:core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
