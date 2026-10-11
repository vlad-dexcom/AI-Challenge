plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.aigateway.MainKt")
}

dependencies {
    implementation(project(":core:llm"))
    implementation(project(":rag:tools"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
