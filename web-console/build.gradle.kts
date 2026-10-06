plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.webconsole.MainKt")
}

// Relative paths (rag/corpus, rag/index, rag/sessions) resolve against the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":rag:tools"))

    testImplementation(testFixtures(project(":rag:core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
