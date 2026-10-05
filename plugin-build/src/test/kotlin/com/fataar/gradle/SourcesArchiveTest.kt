package com.fataar.gradle

import java.io.File
import java.util.TimeZone
import org.gradle.api.GradleException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourcesArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source(name: String, entries: Map<String, String>): File = temporary.newFile(name).also {
        it.writeBytes(SourcesArchive.zip(entries.mapValues { item -> item.value.toByteArray() }))
    }
    private fun merge(vararg inputs: MergeInput): Map<String, ByteArray> {
        val output = File(temporary.root, "out.jar")
        SourcesArchive.merge(inputs.toList(), inputs.map { it.owner }.toSet(), output, File(temporary.root, "report.txt"))
        return AarMerger.readZip(output.readBytes(), "output")
    }
    @Test fun mergesCodeByteExactlyAndKeepsEachProducersLicense() {
        val first = source("a.jar", mapOf("pkg/A.java" to "// first\r\npackage pkg; class A {}", "LICENSE" to "A license"))
        val second = source("b.jar", mapOf("pkg/B.kt" to "package pkg\nclass B", "LICENSE" to "B license"))
        val output = merge(MergeInput("a", first), MergeInput("b", second))
        assertEquals("// first\r\npackage pkg; class A {}", output.getValue("pkg/A.java").decodeToString())
        assertEquals("package pkg\nclass B", output.getValue("pkg/B.kt").decodeToString())
        assertEquals(setOf("A license", "B license"), output.filterKeys { it.endsWith("/LICENSE") }.values.map { it.decodeToString() }.toSet())
        assertFalse(output.keys.any { it.endsWith(".class") })
    }
    @Test fun missingSourcesAndConflictingSourcesNameTheOwners() {
        val input = source("a.jar", mapOf("pkg/A.java" to "a"))
        val missing = assertThrows(GradleException::class.java) {
            SourcesArchive.merge(listOf(MergeInput("a", input)), setOf("a", "missing"),
                File(temporary.root, "out.jar"), File(temporary.root, "report.txt"))
        }
        assertTrue(missing.message!!.contains("FATAAR_MISSING_SOURCES")); assertTrue(missing.message!!.contains("missing"))
        val duplicate = source("b.jar", mapOf("pkg/A.java" to "b"))
        val conflict = assertThrows(GradleException::class.java) { merge(MergeInput("a", input), MergeInput("b", duplicate)) }
        assertTrue(conflict.message!!.contains("FATAAR_SOURCE_CONFLICT"))
        assertTrue(conflict.message!!.contains("pkg/A.java: a and b"))
    }
    @Test fun rejectsTraversalBinaryAndBrokenInputArchives() {
        for ((name, code) in listOf("../A.java" to "INVALID_SOURCE_ARCHIVE", "A.class" to "BINARY_IN_SOURCES")) {
            val input = source(code + ".jar", mapOf(name to "data"))
            assertTrue(assertThrows(GradleException::class.java) { merge(MergeInput("child", input)) }
                .message!!.contains("FATAAR_$code"))
        }
        val broken = temporary.newFile("broken.jar").also { it.writeText("not a ZIP") }
        assertTrue(assertThrows(GradleException::class.java) { merge(MergeInput("child", broken)) }
            .message!!.contains("FATAAR_INVALID_SOURCE_ARCHIVE"))
    }
    @Test fun archiveBytesAreIndependentOfTimezoneAndInputOrder() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val first = SourcesArchive.zip(linkedMapOf("B.java" to byteArrayOf(2), "A.java" to byteArrayOf(1)))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertArrayEquals(first, SourcesArchive.zip(linkedMapOf("A.java" to byteArrayOf(1), "B.java" to byteArrayOf(2))))
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun ignoresEmptyGeneratedAidlMarkersButPreservesHandwrittenSourceAndEmptyStaticFiles() {
        val handwritten = temporary.newFolder("java")
        val generated = temporary.newFolder("generated")
        File(handwritten, "Parcel.java").writeText("class Parcel {}")
        File(handwritten, "Empty.java").writeText("")
        File(generated, "Parcel.java").writeText("")
        val output = File(temporary.root, "out.jar")
        SourcesArchive.export(setOf(handwritten, generated), "module", "release", "1", output, setOf(handwritten))
        val entries = AarMerger.readZip(output.readBytes(), "output")
        assertEquals("class Parcel {}", entries.getValue("Parcel.java").decodeToString())
        assertArrayEquals(byteArrayOf(), entries.getValue("Empty.java"))
    }
    @Test fun consumerGeneratedBindingTypesAreExcludedOnlyFromGeneratedRoots() {
        val handwritten = temporary.newFolder("handwritten")
        val generated = temporary.newFolder("processor")
        File(handwritten, "pkg/BR.java").apply { parentFile.mkdirs(); writeText("package pkg; class BR {}") }
        File(generated, "other/BR.java").apply { parentFile.mkdirs(); writeText("generated field numbering") }
        File(generated, "androidx/databinding/DataBindingComponent.java").apply { parentFile.mkdirs(); writeText("generated interface") }
        File(generated, "pkg/Mapper.java").apply { parentFile.mkdirs(); writeText("package pkg; class Mapper {}") }
        val output = File(temporary.root, "binding.jar")
        SourcesArchive.export(setOf(handwritten, generated), "owner", "release", "1", output, setOf(handwritten), true)
        val entries = AarMerger.readZip(output.readBytes(), "output")
        assertTrue(entries.containsKey("pkg/BR.java"))
        assertTrue(entries.containsKey("pkg/Mapper.java"))
        assertFalse(entries.containsKey("other/BR.java"))
        assertFalse(entries.containsKey("androidx/databinding/DataBindingComponent.java"))
    }
}
