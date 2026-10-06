package com.example.core.platform

import kotlinx.io.files.Path
import java.io.File

/** Bridges JVM-only code (CLI, web console, Android `filesDir`) to the multiplatform [Path] API. */
fun File.toKxPath(): Path = Path(path)
