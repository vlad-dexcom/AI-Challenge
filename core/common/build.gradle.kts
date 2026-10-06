plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.io.core)
    api(libs.ktor.client.core)
    // Only the JVM `platform` package touches the engine; see HttpEngine.kt.
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
}
