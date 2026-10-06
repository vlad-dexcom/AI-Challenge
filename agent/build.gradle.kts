plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:llm"))
    api(project(":rag:core"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    // MCP Kotlin SDK, used as an MCP *client* (see mcp/). Ktor 3.x is the version it is compiled against.
    implementation(libs.mcp.kotlin.sdk.client)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.sse) // required by the MCP SDK's StreamableHttpClientTransport

    testImplementation(testFixtures(project(":rag:core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // Embedded MCP *server* so KotlinSdkMcpGateway is tested end-to-end against a real server.
    testImplementation(libs.mcp.kotlin.sdk.server)
    testImplementation(libs.ktor.server.cio)
}
