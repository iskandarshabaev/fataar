package com.fataar.gradle

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal object AarInventory {
    fun inspect(aar: File): String {
        val lines = sortedSetOf<String>()
        val classes = mutableMapOf<String, String>()
        ZipFile(aar).use { zip ->
            require(zip.getEntry("AndroidManifest.xml") != null) {
                "FATAAR_INVALID_AAR: AndroidManifest.xml is missing"
            }
            val seen = mutableSetOf<String>()
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                require(seen.add(entry.name)) { "FATAAR_DUPLICATE_ENTRY: " + entry.name }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                lines += "entry " + entry.name + " sha256=" + sha256(bytes)
                if (entry.name == "classes.jar" ||
                    (entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))) {
                    ZipInputStream(bytes.inputStream()).use { jar ->
                        var item = jar.nextEntry
                        while (item != null) {
                            if (!item.isDirectory && item.name.endsWith(".class")) {
                                val previous = classes.putIfAbsent(item.name, entry.name)
                                require(previous == null) {
                                    "FATAAR_DUPLICATE_CLASS: " + item.name +
                                        " in " + previous + " and " + entry.name
                                }
                                lines += "class " + item.name + " from=" + entry.name
                            }
                            item = jar.nextEntry
                        }
                    }
                }
            }
        }
        return "format=1\ncoverage=archive-inventory-only\n" + lines.joinToString("\n", postfix = "\n")
    }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
