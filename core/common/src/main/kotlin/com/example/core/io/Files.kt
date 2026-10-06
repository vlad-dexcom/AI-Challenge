package com.example.core.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** Multiplatform file helpers on top of kotlinx-io, so stores need no `java.io.File`. */
fun Path.exists(): Boolean = SystemFileSystem.exists(this)

fun Path.isFile(): Boolean = SystemFileSystem.metadataOrNull(this)?.isRegularFile == true

fun Path.isDirectory(): Boolean = SystemFileSystem.metadataOrNull(this)?.isDirectory == true

fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

/** Overwrites the file, creating missing parent directories first. */
fun Path.writeText(text: String) {
    parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(this).buffered().use { it.writeString(text) }
}

fun Path.delete() = SystemFileSystem.delete(this, mustExist = false)

fun Path.createDirectories() = SystemFileSystem.createDirectories(this)

/** Names (not full paths) of the direct children of a directory, sorted; empty if it does not exist. */
fun Path.listNames(): List<String> =
    if (!isDirectory()) emptyList() else SystemFileSystem.list(this).map { it.name }.sorted()

/** Recursively lists regular files below this directory (full paths, unsorted). */
fun Path.walkFiles(): List<Path> =
    if (!isDirectory()) emptyList()
    else SystemFileSystem.list(this).flatMap { if (it.isDirectory()) it.walkFiles() else listOf(it) }

/** Replaces [target] with this file in one step so readers never see a half-written file. */
fun Path.atomicMoveTo(target: Path) = SystemFileSystem.atomicMove(this, target)

/** Path of this file relative to [base] using `/` separators. */
fun Path.relativeTo(base: Path): String = toString().removePrefix(base.toString()).trimStart('/', '\\').replace('\\', '/')

val Path.extension: String get() = name.substringAfterLast('.', "")

operator fun Path.div(child: String): Path = Path(this, child)
