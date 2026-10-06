package com.example.core.platform

import java.text.Normalizer

fun normalizeNfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)

fun normalizeNfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
