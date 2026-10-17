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
    mainClass.set("com.example.devassistant.MainKt")
}

// Relative paths (README.md, docs/, rag/index-docs) resolve against the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in` // the REPL reads stdin
}

dependencies {
    api(project(":agent"))
    api(project(":rag:tools"))

    testImplementation(testFixtures(project(":rag:core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
