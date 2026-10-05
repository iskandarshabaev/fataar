package com.fataar.gradle

import com.google.gson.GsonBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException

/** Original sources stay byte-for-byte intact. This is a source attachment, not a rebuilt source project. */
internal object SourcesArchive {
    const val ORIGIN = "META-INF/fataar/source-origin.json"
    const val INDEX = "META-INF/fataar/sources.json"
    private val sourceExtensions = setOf("java", "kt", "kts", "aidl", "groovy", "scala", "proto")
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private fun fail(code: String, message: String): Nothing = throw GradleException("FATAAR_$code: $message")

    fun isSource(path: String) = path.substringAfterLast('.', "") in sourceExtensions

    fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { output ->
            entries.toSortedMap().forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path).apply { setTimeLocal(LocalDateTime.of(1980, 2, 1, 0, 0)) })
                output.write(bytes); output.closeEntry()
            }
        }
    }.toByteArray()

    private fun add(entries: MutableMap<String, ByteArray>, owners: MutableMap<String, String>,
                    path: String, bytes: ByteArray, owner: String) {
        val previous = entries[path]
        if (previous != null && !previous.contentEquals(bytes))
            fail("SOURCE_CONFLICT", "$path: ${owners[path]} and $owner have different content")
        entries[path] = bytes
        owners.putIfAbsent(path, owner)
    }

    fun export(roots: Set<File>, owner: String, variant: String, version: String, output: File,
               staticRoots: Set<File> = emptySet(), dataBinding: Boolean = false) {
        val entries = sortedMapOf<String, ByteArray>()
        val owners = mutableMapOf<String, String>()
        roots.filter { it.isDirectory }.sortedBy { it.invariantSeparatorsPath }.forEach { root ->
            root.walkTopDown().filter { it.isFile && isSource(it.name) }.forEach sourceFile@ { file ->
                // AIDL emits empty Java dependency markers for parcelables implemented
                // by handwritten Java. Those markers are not compilation units.
                if (root !in staticRoots && file.extension == "java" && file.length() == 0L) return@sourceFile
                val path = file.relativeTo(root).invariantSeparatorsPath
                // These Data Binding types are excluded from library AARs and regenerated
                // by the consuming app. Each producer's BR has different field numbering.
                if (dataBinding && root !in staticRoots && (file.name == "BR.java" ||
                        path == "androidx/databinding/DataBindingComponent.java")) return@sourceFile
                add(entries, owners, path, file.readBytes(), "$owner/${root.name}")
            }
        }
        entries[ORIGIN] = gson.toJson(linkedMapOf("format" to 1, "owner" to owner,
            "variant" to variant, "version" to version, "sources" to entries.keys.toList())).toByteArray(UTF_8)
        output.parentFile.mkdirs(); output.writeBytes(zip(entries))
    }

    private fun read(input: MergeInput): Map<String, ByteArray> {
        try {
            return ZipFile(input.file).use { zip ->
                val result = sortedMapOf<String, ByteArray>()
                zip.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    if (name.startsWith('/') || '\\' in name || name.split('/').any { it == "." || it == ".." })
                        fail("INVALID_SOURCE_ARCHIVE", "${input.owner}: unsafe entry $name")
                    if (!entry.isDirectory && result.put(name, zip.getInputStream(entry).use { it.readBytes() }) != null)
                        fail("INVALID_SOURCE_ARCHIVE", "${input.owner}: duplicate entry $name")
                }
                result
            }
        } catch (error: java.util.zip.ZipException) {
            fail("INVALID_SOURCE_ARCHIVE", "${input.owner}: ${error.message}")
        }
    }

    fun merge(inputs: List<MergeInput>, expected: Set<String>, output: File, report: File) {
        val actual = inputs.map { it.owner }.toSet()
        val missing = expected - actual
        if (missing.isNotEmpty()) fail("MISSING_SOURCES", "No source archive for ${missing.sorted()}. " +
            "Apply com.fataar.embeddable to Android projects, enable java.withSourcesJar() for JVM projects, " +
            "or provide a sources variant for embedded Maven libraries. External api/implementation libraries are not embedded.")
        val unexpected = actual - expected
        if (unexpected.isNotEmpty()) fail("UNEXPECTED_SOURCES", "Source artifacts are not embedded: ${unexpected.sorted()}")
        if (inputs.groupBy { it.owner }.any { it.value.size != 1 })
            fail("AMBIGUOUS_SOURCES", "Expected exactly one source archive per embedded component")
        val entries = sortedMapOf<String, ByteArray>()
        val owners = mutableMapOf<String, String>()
        val records = inputs.sortedBy { it.owner }.map { input ->
            val sources = read(input)
            val prefix = "META-INF/fataar/inputs/" + MessageDigest.getInstance("SHA-256")
                .digest(input.owner.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) } + "/"
            val paths = mutableListOf<String>()
            sources.forEach { (path, bytes) ->
                when {
                    path.endsWith(".class") -> fail("BINARY_IN_SOURCES", "${input.owner}: $path is compiled bytecode")
                    path == "META-INF/MANIFEST.MF" -> Unit
                    isSource(path) -> { add(entries, owners, path, bytes, input.owner); paths += path }
                    else -> entries[prefix + path] = bytes // Preserve licenses and producer origin without collisions.
                }
            }
            linkedMapOf("owner" to input.owner, "sources" to paths.sorted(),
                "sha256" to MessageDigest.getInstance("SHA-256").digest(input.file.readBytes())
                    .joinToString("") { "%02x".format(it) })
        }
        entries[INDEX] = gson.toJson(linkedMapOf("format" to 1, "inputs" to records,
            "sourceCount" to entries.keys.count(::isSource))).toByteArray(UTF_8)
        output.parentFile.mkdirs(); output.writeBytes(zip(entries))
        report.parentFile.mkdirs(); report.writeText("format=1\ncoverage=original-source-archives\n" +
            records.joinToString("") { "source ${it["owner"]}: ${(it["sources"] as List<*>).size} files\n" } +
            "sourceCount=${entries.keys.count(::isSource)}\nstatus=PASS\n")
    }
}
