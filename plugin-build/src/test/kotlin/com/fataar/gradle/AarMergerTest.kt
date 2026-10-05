package com.fataar.gradle

import java.io.File
import org.gradle.api.GradleException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.*

class AarMergerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun aar(name: String, namespace: String, entries: Map<String, ByteArray> = emptyMap()): File =
        archive(temporary.newFile("$name.aar"), mapOf("AndroidManifest.xml" to
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$namespace"><uses-sdk android:minSdkVersion="24"/></manifest>""".toByteArray()) + entries)
    private fun merge(main: File, vararg dependencies: File): File = File(temporary.root, "result.aar").also {
        AarMerger.merge(main, dependencies.map { MergeInput(it.nameWithoutExtension, it) }, it, File(temporary.root, "work"))
    }
    private fun referencing(name: String, field: String = "title"): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "resource", "()I", null, null).apply {
            visitCode(); visitFieldInsn(Opcodes.GETSTATIC, "child/R\$string", field, "I")
            visitInsn(Opcodes.IRETURN); visitMaxs(1, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
    @Test fun rewritesHostAndEmbeddedRAndProducesDeterministicArchive() {
        val main = aar("sdk", "sdk", mapOf("classes.jar" to zipBytes(mapOf("sdk/Facade.class" to referencing("sdk/Facade")))))
        val child = aar("child", "child", mapOf("classes.jar" to zipBytes(mapOf("child/Impl.class" to referencing("child/Impl"))),
            "R.txt" to "int string title 0x0\n".toByteArray(),
            "res/values/values.xml" to "<resources><string name=\"title\">Child</string></resources>".toByteArray()))
        val first = merge(main, child).readBytes()
        val classes = AarMerger.readZip(AarMerger.readZip(first, "aar").getValue("classes.jar"), "jar")
        for (name in listOf("sdk/Facade.class", "child/Impl.class")) {
            val references = mutableListOf<String>()
            ClassReader(classes.getValue(name)).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitFieldInsn(op: Int, owner: String, n: String, d: String) { references += owner }
                    }
            }, 0)
            assertEquals(listOf("sdk/R\$string"), references)
        }
        assertArrayEquals(first, merge(main, child).readBytes())
    }
    @Test fun rejectsMissingRSymbolBeforePublishing() {
        val host = aar("sdk", "sdk", mapOf("classes.jar" to zipBytes(mapOf("sdk/Api.class" to referencing("sdk/Api", "absent")))))
        val child = aar("child", "child")
        assertTrue(assertThrows(GradleException::class.java) { merge(host, child) }.message!!.contains("FATAAR_MISSING_RESOURCE_SYMBOL"))
    }
    @Test fun reportsResourceAndClassConflictsWithBothOwners() {
        val values = mapOf("res/values/values.xml" to "<resources><string name=\"same\">value</string></resources>".toByteArray())
        val host = aar("sdk", "sdk", values)
        val child = aar("child", "child", values.mapValues { it.value.decodeToString().replace(">value<", ">different<").toByteArray() })
        val resource = assertThrows(GradleException::class.java) { merge(host, child) }.message!!
        assertTrue(resource.contains("FATAAR_RESOURCE_CONFLICT")); assertTrue(resource.contains("sdk and child"))
        val classes = mapOf("classes.jar" to zipBytes(mapOf("example/Same.class" to classBytes("example/Same"))))
        val first = aar("first", "first", classes)
        val second = aar("second", "second", classes)
        assertTrue(assertThrows(GradleException::class.java) { merge(first, second) }.message!!.contains("FATAAR_DUPLICATE_CLASS"))
    }
    @Test fun mergesServiceDescriptorsAndRejectsUnknownPayload() {
        val key = "META-INF/services/example.Service"
        val first = aar("sdk", "sdk", mapOf("classes.jar" to zipBytes(mapOf(
            "example/One.class" to classBytes("example/One"), key to "example.One\n".toByteArray()))))
        val second = aar("child", "child", mapOf("classes.jar" to zipBytes(mapOf(
            "example/Two.class" to classBytes("example/Two"), key to "# providers\nexample.Two\n".toByteArray()))))
        val jar = AarMerger.readZip(AarMerger.readZip(merge(first, second).readBytes(), "aar").getValue("classes.jar"), "jar")
        assertEquals("example.One\nexample.Two\n", jar.getValue(key).decodeToString())
        val unsupported = aar("native", "native", mapOf("unsupported/payload.bin" to byteArrayOf(1)))
        assertTrue(assertThrows(GradleException::class.java) { merge(first, unsupported) }.message!!.contains("FATAAR_UNSUPPORTED_PAYLOAD"))
    }

    @Test fun preservesAidlAndRejectsConflictingDeclarations() {
        val path = "aidl/child/Packet.aidl"
        val bytes = "package child; parcelable Packet;".toByteArray()
        val host = aar("sdk", "sdk", mapOf(path to bytes))
        val child = aar("child", "child", mapOf(path to bytes))
        assertArrayEquals(bytes, AarMerger.readZip(merge(host, child).readBytes(), "aar").getValue(path))
        val conflict = aar("other", "other", mapOf(path to "package child; parcelable Packet; // different".toByteArray()))
        val error = assertThrows(GradleException::class.java) { merge(host, conflict) }.message!!
        assertTrue(error.contains("FATAAR_PAYLOAD_CONFLICT")); assertTrue(error.contains("sdk and other"))
    }

    private fun manifestAar(name: String, manifest: String): File = archive(temporary.newFile("$name.aar"),
        mapOf("AndroidManifest.xml" to manifest.toByteArray()))

    @Test fun honorsManifestToolsAndPreservesConsumerPlaceholders() {
        val host = manifestAar("sdk", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools" package="sdk"><application>
            <meta-data android:name="override" android:value="sdk-value" tools:replace="android:value"/>
            <meta-data android:name="removed" tools:node="remove"/>
            </application></manifest>""")
        val child = manifestAar("child", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="child">
            <application><meta-data android:name="override" android:value="child-value"/>
            <meta-data android:name="removed" android:value="discard-me"/>
            <provider android:name=".Provider" android:authorities="${'$'}{applicationId}.fixture" android:exported="false"/>
            </application></manifest>""")
        val manifest = AarMerger.readZip(merge(host, child).readBytes(), "aar").getValue("AndroidManifest.xml").decodeToString()
        assertTrue(manifest.contains("sdk-value")); assertFalse(manifest.contains("child-value"))
        assertFalse(manifest.contains("discard-me")); assertTrue(manifest.contains("child.Provider"))
        assertTrue(manifest.contains("${'$'}{applicationId}.fixture"))
    }

    @Test fun rejectsOverlappingProviderAuthorityLists() {
        val host = manifestAar("sdk", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="sdk">
            <application><provider android:name="sdk.One" android:authorities="${'$'}{applicationId}.one;${'$'}{applicationId}.shared"/></application></manifest>""")
        val child = manifestAar("child", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="child">
            <application><provider android:name="child.Two" android:authorities="${'$'}{applicationId}.shared"/></application></manifest>""")
        val failure = assertThrows(GradleException::class.java) { merge(host, child) }.message!!
        assertTrue(failure.contains("FATAAR_DUPLICATE_PROVIDER_AUTHORITY"))
        assertTrue(failure.contains("sdk.One") && failure.contains("child.Two"))
    }

    @Test fun rejectsIncompatibleEmbeddedMinSdk() {
        val host = aar("sdk", "sdk")
        val child = manifestAar("child", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="child">
            <uses-sdk android:minSdkVersion="25"/></manifest>""")
        val failure = assertThrows(GradleException::class.java) { merge(host, child) }.message!!
        assertTrue(failure.contains("FATAAR_MIN_SDK_CONFLICT"))
        assertTrue(failure.contains("child requires 25, SDK declares 24"))
    }

    @Test fun reportsManifestAttributeConflictsWithOwners() {
        fun manifest(namespace: String) = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$namespace">
            <application><meta-data android:name="same" android:value="$namespace"/></application></manifest>"""
        val host = manifestAar("sdk", manifest("sdk"))
        val child = manifestAar("child", manifest("child"))
        val failure = assertThrows(GradleException::class.java) { merge(host, child) }.message!!
        assertTrue(failure.contains("FATAAR_MANIFEST_CONFLICT"))
        assertTrue(failure.contains("inputs: sdk, child") && failure.contains("same"))
    }

    @Test fun doesNotTreatQueryProviderAsInstalledComponent() {
        val host = manifestAar("sdk", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="sdk">
            <queries><provider android:authorities="${'$'}{applicationId}.fixture"/></queries>
            <application><provider android:name="sdk.Provider" android:authorities="${'$'}{applicationId}.fixture"/></application></manifest>""")
        val bytes = AarMerger.readZip(merge(host).readBytes(), "aar").getValue("AndroidManifest.xml")
        val manifest = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(bytes.inputStream()).documentElement
        assertEquals(1, manifest.getElementsByTagName("queries").length)
        assertEquals(2, manifest.getElementsByTagName("provider").length)
    }
}
