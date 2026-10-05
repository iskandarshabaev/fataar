package com.fataar.gradle
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AarInventoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun inventoryIsIndependentOfArchiveOrderAndLocation() {
        val entries = linkedMapOf("AndroidManifest.xml" to "<manifest/>".toByteArray(), "classes.jar" to jar("a/Marker.class"))
        val first = archive(temporary.newFile(), entries)
        val second = archive(temporary.newFile(), entries.entries.reversed().associate { it.toPair() })
        assertEquals(AarInventory.inspect(first), AarInventory.inspect(second))
        assertTrue(AarInventory.inspect(first).contains("class a/Marker.class from=classes.jar"))
    }
    @Test fun rejectsMissingManifest() {
        val aar = archive(temporary.newFile(), mapOf("classes.jar" to jar("a/A.class")))
        val error = assertThrows(IllegalArgumentException::class.java) { AarInventory.inspect(aar) }
        assertTrue(error.message!!.contains("FATAAR_INVALID_AAR"))
    }
    @Test fun reportsBothOwnersOfDuplicateClass() {
        val aar = archive(temporary.newFile(), mapOf(
            "AndroidManifest.xml" to "<manifest/>".toByteArray(),
            "classes.jar" to jar("a/Duplicate.class"),
            "libs/extra.jar" to jar("a/Duplicate.class"),
        ))
        val error = assertThrows(IllegalArgumentException::class.java) { AarInventory.inspect(aar) }
        assertTrue(error.message!!.contains("FATAAR_DUPLICATE_CLASS: a/Duplicate.class in classes.jar and libs/extra.jar"))
    }
}
internal fun jar(className: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry(className).apply { time = 0 })
        zip.write(byteArrayOf(0, 1, 2)); zip.closeEntry()
    }
    return bytes.toByteArray()
}
internal fun archive(file: File, entries: Map<String, ByteArray>): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(bytes); zip.closeEntry()
        }
    }
    return file
}
