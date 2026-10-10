package com.example.aigateway

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object KeyHasher {
    fun sha256Hex(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    fun newKey(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return "sk-lan-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Looks up a presented key among the configured hashes; every entry is compared, in constant time. */
class KeyStore(private val keys: List<KeyEntry>) {
    private val hashes = keys.map { it.name to decode(it.sha256) }

    /** Name of the matching device key, or null. Never short-circuits on the first match. */
    fun authenticate(presented: String): String? {
        val digest = MessageDigest.getInstance("SHA-256").digest(presented.toByteArray())
        var match: String? = null
        for ((name, hash) in hashes) {
            if (MessageDigest.isEqual(digest, hash) && match == null) match = name
        }
        return match
    }

    private fun decode(hex: String): ByteArray {
        val h = hex.trim().lowercase()
        return if (h.length == 64 && h.all { it in "0123456789abcdef" }) ByteArray(32) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        else ByteArray(32) // never matches a real digest in practice
    }
}
