package com.example.aigateway

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  ai-gateway [--config FILE] [--host H] [--port P]     run the gateway
  ai-gateway keygen --name DEVICE --config FILE         create a device key; prints it once, stores only its SHA-256"""

fun main(args: Array<String>) {
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    when {
        "--help" in args || "-h" in args -> println(USAGE)
        args.firstOrNull() == "keygen" -> keygen(opt("--name"), opt("--config"))
        else -> run(opt("--config"), opt("--host"), opt("--port")?.toIntOrNull())
    }
}

private fun run(configPath: String?, host: String?, port: Int?) {
    val base = try { GatewayConfig.load(configPath) } catch (e: Exception) {
        System.err.println("Config error: ${e.message}"); exitProcess(2)
    }
    val config = base.copy(host = host ?: base.host, port = port ?: base.port)
    if (config.keys.isEmpty()) System.err.println("WARNING: no API keys configured - every request will be refused. Run: ai-gateway keygen --name phone --config <file>")
    val gateway = Gateway(config)
    System.err.println("ai-gateway listening on http://${config.host}:${config.port}  upstream=${config.upstreamUrl}  models=${config.models}  numCtx=${config.numCtx}")
    embeddedServer(CIO, host = config.host, port = config.port) { gateway.install(this) }.start(wait = true)
}

private fun keygen(name: String?, configPath: String?) {
    if (name.isNullOrBlank() || configPath == null) { System.err.println(USAGE); exitProcess(2) }
    val file = File(configPath)
    val config = if (file.exists()) GatewayConfig.json.decodeFromString(GatewayConfig.serializer(), file.readText()) else GatewayConfig()
    if (config.keys.any { it.name == name }) { System.err.println("Device '$name' already has a key; pick another name or remove it from the config."); exitProcess(1) }
    val key = KeyHasher.newKey()
    file.absoluteFile.parentFile?.mkdirs()
    file.writeText(GatewayConfig.json.encodeToString(GatewayConfig.serializer(), config.copy(keys = config.keys + KeyEntry(name, KeyHasher.sha256Hex(key)))))
    runCatching { file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true) }
    println(key)
}
