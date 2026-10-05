package com.fataar.gradle

import org.gradle.api.GradleException
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeMetadataTest {
    private fun source(owner: String, pkg: String, module: String, exports: String = "[]", dependencies: String = "[]") =
        PrefabMerger.Source(owner, mapOf(
            "prefab/prefab.json" to """{"name":"$pkg","schema_version":2,"dependencies":$dependencies}""".toByteArray(),
            "prefab/modules/$module/module.json" to """{"export_libraries":$exports,"android":{"export_libraries":$exports}}""".toByteArray(),
            "prefab/modules/$module/include/api.h" to "// $owner\n".toByteArray()))
    private fun rejected(code: String, action: () -> Unit) {
        assertTrue(assertThrows(GradleException::class.java, action).message!!.contains(code))
    }
    @Test fun flattensInternalReferencesAndKeepsExternalApiOwnership() {
        val sources = listOf(source("first", "first", "math"),
            source("second", "second", "bridge", "[\"//first:math\",\"//public:shared\",\"-lm\"]", "[\"first\",\"public\"]"))
        val external = source("external", "public", "shared")
        val merged = PrefabMerger.merge(sources, "sdk", listOf(external))
        assertEquals("[\"public\"]", PrefabMerger.json(merged.getValue("prefab/prefab.json"), "test").get("dependencies").toString())
        val metadata = PrefabMerger.json(merged.getValue("prefab/modules/bridge/module.json"), "test")
        assertEquals("[\":math\",\"//public:shared\",\"-lm\"]", metadata.get("export_libraries").toString())
        assertEquals(metadata.get("export_libraries"), metadata.getAsJsonObject("android").get("export_libraries"))
        assertArrayEquals(sources[0].entries.getValue("prefab/modules/math/include/api.h"), merged.getValue("prefab/modules/math/include/api.h"))
        assertFalse(merged.keys.any { it.contains("/shared/") })
    }
    @Test fun rejectsDuplicateModuleNamesWithBothOwners() {
        val error = assertThrows(GradleException::class.java) {
            PrefabMerger.merge(listOf(source("one", "one", "same"), source("two", "two", "same")), "sdk")
        }.message!!
        assertTrue(error.contains("FATAAR_PREFAB_MODULE_CONFLICT")); assertTrue(error.contains("one and two"))
    }
    @Test fun rejectsUnaccountedExternalPackagesAndBrokenInternalReferences() {
        rejected("missing external package public") { PrefabMerger.merge(listOf(source("one", "one", "a", "[\"//public:missing\"]")), "sdk") }
        rejected("absent module :missing") { PrefabMerger.merge(listOf(source("one", "one", "a", "[\":missing\"]")), "sdk") }
        rejected("has no module missing") { PrefabMerger.merge(listOf(source("one", "one", "a", "[\"//public:missing\"]")), "sdk", listOf(source("external", "public", "other"))) }
    }
    @Test fun rejectsUnsupportedPrefabSchemaAndMalformedJson() {
        val good = source("one", "one", "a")
        for (data in listOf("{", "{\"name\":\"one\",\"schema_version\":1}")) {
            rejected("FATAAR_PREFAB_METADATA") { PrefabMerger.merge(listOf(good.copy(entries = good.entries + ("prefab/prefab.json" to data.toByteArray()))), "sdk") }
        }
    }
    @Test fun rejectsEmptyHeaderOnlyAndUnindexedBinaryPayload() {
        rejected("header-only module has no headers") { NativePayload.validate(mapOf("prefab/modules/empty/module.json" to "{}".toByteArray()), emptyMap(), 24) }
        rejected("binaries have no ABI metadata") { NativePayload.validate(mapOf("prefab/modules/empty/module.json" to "{}".toByteArray(), "prefab/modules/empty/libs/android.x86/libempty.so" to byteArrayOf(1)), emptyMap(), 24) }
    }
    private fun loader(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sdk/Loader", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode(); visitLdcInsn("missing"); visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 0); visitEnd()
        }; visitEnd()
    }.toByteArray()
    @Test fun namesTheMissingLiteralLoaderLibraryAndClass() {
        val error = assertThrows(GradleException::class.java) {
            NativePayload.validate(emptyMap(), mapOf("sdk/Loader.class" to loader()), 24, setOf("arm64-v8a"))
        }.message!!
        assertTrue(error.contains("jni/arm64-v8a/libmissing.so")); assertTrue(error.contains("sdk/Loader.class#<clinit>"))
    }
    @Test fun rejectsTruncatedAndMismatchedElfBeforePublishing() {
        rejected("not ELF") { NativeElf.inspect(byteArrayOf(1), "x86_64", "fixture") }
        val bytes = ByteArray(64)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x464c457f); put(4, 2); put(5, 1); putShort(16, 3); putShort(18, 183)
        }
        rejected("machine does not match") { NativeElf.inspect(bytes, "x86_64", "fixture") }
        rejected("invalid program headers") { NativeElf.inspect(bytes, "arm64-v8a", "fixture") }
    }
    @Test fun rejectsEmptyAndTruncatedStaticArchives() {
        rejected("contains no ELF objects") { NativeElf.inspectArchive("!<arch>\n".toByteArray(), "x86_64", "fixture") }
        rejected("truncated archive member") { NativeElf.inspectArchive("!<arch>\na".toByteArray(), "x86_64", "fixture") }
        rejected("not a static archive") { NativeElf.inspectArchive(ByteArray(8), "x86_64", "fixture") }
    }
}
