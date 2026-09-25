import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.geminichat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.geminichat"
        minSdk = 26
        targetSdk = 34
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Ktor client (plain REST calls, no Gemini SDK). Uses the OkHttp engine — the
    // legacy Android engine has known issues where timeouts aren't reliably enforced,
    // which can cause a stalled connection to hang forever instead of failing.
    // Ktor 3.5.1 (bumped from 2.3.12 on Day 16) is the version the MCP Kotlin SDK
    // (io.modelcontextprotocol:kotlin-sdk-client) is compiled against.
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
    // Server-Sent Events plugin, required by the MCP SDK's StreamableHttpClientTransport.
    implementation("io.ktor:ktor-sse:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.core:core-ktx:1.13.1")

    // Markdown rendering for chat messages (wraps Markwon via an AndroidView TextView).
    implementation("com.github.jeziellago:compose-markdown:0.7.2")

    // Day 16: MCP Kotlin SDK, used as an MCP *client* to connect to remote MCP servers
    // (see app/src/main/java/com/example/geminichat/mcp/). Client-only artifact — no
    // server-side APIs are pulled in.
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")

    // Unit tests for the agent layer (plain JVM, no Android/network dependency needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
