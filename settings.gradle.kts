pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // required for compose-markdown
    }
}

rootProject.name = "PersonalTrainer"
include(":app")
include(":core:common")
include(":core:llm")
include(":agent")
include(":rag:core")
include(":rag:tools")
include(":web-console")
