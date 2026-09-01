import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.core:core-ktx:1.13.1")

    // Markdown rendering for chat messages (wraps Markwon via an AndroidView TextView).
    implementation("com.github.jeziellago:compose-markdown:0.7.2")
}
