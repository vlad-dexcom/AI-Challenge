package com.example.core.text

import com.example.core.platform.normalizeNfd
import com.example.core.platform.normalizeNfkc

/** Unicode normalisation (backed by `java.text.Normalizer` on the JVM, see the `platform` package). */
object TextNormalizer {
    fun nfd(s: String): String = normalizeNfd(s)
    fun nfkc(s: String): String = normalizeNfkc(s)
}
