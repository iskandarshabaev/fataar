package com.fataar.gradle

import org.gradle.api.GradleException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataBindingMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val path = "data-binding-base-class-log/child-binding_classes.json"
    private val log = """{"mappings":{"title":{"module_package":"child","qualified_name":"child.TitleBinding","variables":{},"implementations":[{"qualified_name":"child.TitleBindingImpl"}]}}}"""
    private fun entries() = mapOf(path to log.toByteArray(Charsets.UTF_16),
        "data-binding/child-br.bin" to byteArrayOf(0xac.toByte(), 0xed.toByte(), 0, 5),
        "data-binding/child-setter_store.json" to """{"version":5,"useAndroidX":true}""".toByteArray())
    private val classes = setOf("child/DataBinderMapperImpl.class", "child/TitleBinding.class", "child/TitleBindingImpl.class")

    @Test fun redirectsResourcesAndKeepsClassNamesAndUtf16Encoding() {
        val bytes = DataBindingMetadata.rewrite(path, log.toByteArray(Charsets.UTF_16), "child", "sdk", setOf("child", "sdk"))
        assertEquals(0xfe.toByte(), bytes[0])
        val text = bytes.toString(Charsets.UTF_16)
        assertTrue(text.contains("\"module_package\":\"sdk\""))
        assertTrue(text.contains("child.TitleBindingImpl"))
        assertArrayEquals(bytes, DataBindingMetadata.rewrite(path, bytes, "child", "sdk", setOf("child", "sdk")))
    }
    @Test fun validatesPairedMetadataClassesAndResourceSymbols() {
        val values = entries()
        fun check(entries: Map<String,ByteArray> = values, types: Set<String> = classes, symbols: Set<String> = setOf("layout/title")) =
            DataBindingMetadata.validate(entries, types, symbols, values.keys.associateWith { "child" })
        check()
        for (key in values.keys) {
            assertTrue(assertThrows(GradleException::class.java) { check(values - key) }.message!!.contains("FATAAR_DATABINDING_METADATA"))
        }
        assertTrue(assertThrows(GradleException::class.java) { check(types = classes - "child/TitleBindingImpl.class") }.message!!.contains("FATAAR_DATABINDING_CLASS"))
        assertTrue(assertThrows(GradleException::class.java) { check(symbols = emptySet()) }.message!!.contains("missing resource layout/title"))
    }
    @Test fun redirectsImportedPlainViewBindingResourceOwner() {
        val imported = """{"mappings":{"title":{"module_package":"child.databinding","qualified_name":"child.databinding.TitleBinding","implementations":[]}}}"""
        val result = DataBindingMetadata.rewrite(path, imported.toByteArray(), "sdk", "sdk", setOf("child", "sdk"))
        assertTrue(result.decodeToString().contains("\"module_package\":\"sdk\""))
        assertTrue(result.decodeToString().contains("child.databinding.TitleBinding"))
    }
    @Test fun rejectsInvalidJsonNamespaceSerializationAndSetterVersion() {
        fun rejects(path: String, bytes: ByteArray) {
            assertTrue(assertThrows(GradleException::class.java) {
                DataBindingMetadata.rewrite(path, bytes, "child", "sdk", setOf("sdk", "child"))
            }.message!!.contains("FATAAR_DATABINDING_METADATA"))
        }
        rejects(path, "{broken".toByteArray())
        rejects(path, log.replace("\"child\"", "\"unknown\"").toByteArray())
        rejects("data-binding/child-br.bin", byteArrayOf(1, 2, 3, 4))
        rejects("data-binding/child-setter_store.json", """{"version":6,"useAndroidX":true}""".toByteArray())
    }
    @Test fun consumerGeneratedInterfaceRequiresBothMetadataAndMapper() {
        val file = archive(File(temporary.root, "library.aar"), entries())
        val expected = setOf("androidx/databinding/DataBindingComponent")
        assertEquals(expected, ConsumerGeneratedTypes.dataBinding(file, setOf("child/DataBinderMapperImpl")))
        assertTrue(ConsumerGeneratedTypes.dataBinding(file, emptySet()).isEmpty())
        val absent = archive(File(temporary.root, "missing.aar"), entries() - path)
        assertTrue(ConsumerGeneratedTypes.dataBinding(absent, setOf("child/DataBinderMapperImpl")).isEmpty())
    }
    @Test fun externalResourceOwnerKeepsNamespaceAndUsesItsOwnArtifactInventory() {
        val external = mapOf("child" to BindingResourceOwner("module:external:bindings", classes, setOf("layout/title")))
        val bytes = DataBindingMetadata.rewrite(path, log.toByteArray(), "sdk", "sdk", setOf("sdk"), external)
        assertEquals(log, bytes.decodeToString())
        // SDK has no external classes/layouts; external AAR does, and they stay there.
        DataBindingMetadata.validate(mapOf("data-binding-base-class-log/sdk-binding_classes.json" to bytes,
            "data-binding/sdk-br.bin" to entries().getValue("data-binding/child-br.bin"),
            "data-binding/sdk-setter_store.json" to entries().getValue("data-binding/child-setter_store.json")),
            setOf("sdk/DataBinderMapperImpl.class"), emptySet(), emptyMap(), external)
        val imported = """{"mappings":{"title":{"module_package":"child.databinding","qualified_name":"child.databinding.TitleBinding","implementations":[]}}}"""
        assertTrue(DataBindingMetadata.rewrite(path, imported.toByteArray(), "sdk", "sdk", setOf("sdk"), external)
            .decodeToString().contains("\"module_package\":\"child\""))
    }
    @Test fun externalClassesAndResourcesCannotBeSatisfiedBySdkCopies() {
        val values = entries().mapKeys { (key, _) -> key.replace("/child-", "/sdk-") }
        val allClasses = classes + "sdk/DataBinderMapperImpl.class"
        fun check(external: BindingResourceOwner) = DataBindingMetadata.validate(values, allClasses,
            setOf("layout/title"), emptyMap(), mapOf("child" to external))
        val error = assertThrows(GradleException::class.java) {
            check(BindingResourceOwner("module:external:bindings", classes, emptySet()))
        }
        assertTrue(error.message!!.contains("missing resource layout/title in child"))
        assertTrue(assertThrows(GradleException::class.java) {
            check(BindingResourceOwner("module:external:bindings", classes - "child/TitleBindingImpl.class", setOf("layout/title")))
        }.message!!.contains("FATAAR_DATABINDING_CLASS"))
    }

}
