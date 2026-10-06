import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.geminichat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.geminichat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Provide your Gemini API key via -PGEMINI_API_KEY=... or a GEMINI_API_KEY entry in local.properties.
        val geminiApiKey: String = (project.findProperty("GEMINI_API_KEY") as String?)
            ?: (localProperties.getProperty("GEMINI_API_KEY") ?: "")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The Compose compiler version is now driven by the org.jetbrains.kotlin.plugin.compose
    // Gradle plugin (applied above), which pins it to the Kotlin version automatically —
    // composeOptions.kotlinCompilerExtensionVersion is no longer needed/used.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Day 22: bundle the prebuilt (Gemini-embedded, structure-chunked) RAG index as an app asset.
// Copied at build time from rag/index so there is a single committed source of truth.
val copyRagIndex by tasks.registering(Copy::class) {
    from(rootProject.file("rag/index/structure.json"))
    into(layout.buildDirectory.dir("generated/ragAssets/rag"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ragAssets"))
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("Lint", ignoreCase = true) }.configureEach { dependsOn(copyRagIndex) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:llm"))
    implementation(project(":rag:core"))
    implementation(project(":agent"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Ktor client (plain REST calls, no Gemini SDK). The OkHttp engine is used because the
    // legacy Android engine does not reliably enforce timeouts. Ktor 3.x is the version the
    // MCP Kotlin SDK is compiled against.
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.sse) // required by the MCP SDK's StreamableHttpClientTransport
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)

    // Periodic workout-digest aggregation (see agent/workout/WorkoutDigestWorker.kt).
    implementation(libs.androidx.work.runtime.ktx)

    // Markdown rendering for chat messages (wraps Markwon via an AndroidView TextView).
    implementation(libs.compose.markdown)

}
