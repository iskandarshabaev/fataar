package com.fataar.gradle
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbiCacheIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun rejectsRuntimeOnlyApiAndCachesIdentityTransformWithClasspathInvalidation() {
        val directory = temporary.newFolder()
        File(directory, "settings.gradle.kts").writeText("rootProject.name = \"abi-test\"\nbuildCache { local { directory = file(\"cache\") } }\n")
        File(directory, "gradle.properties").writeText("org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx512m\n")
        File(directory, "build.gradle.kts").writeText("""
            import com.fataar.gradle.ValidateAbiTask
            import com.fataar.gradle.DependencyArtifact
            plugins { id("com.fataar") }
            tasks.register<ValidateAbiTask>("verifyAbi") {
                inputAar.set(layout.projectDirectory.file("sdk.aar"))
                outputAar.set(layout.buildDirectory.file("sdk.aar"))
                reportFile.set(layout.buildDirectory.file("abi.txt"))
                runtimeArtifacts.set(listOf(DependencyArtifact("module:test:outside", file("outside.jar"))))
                apiArtifacts.set(if (providers.gradleProperty("exposeApi").isPresent)
                    listOf(DependencyArtifact("module:test:outside", file("outside.jar"))) else emptyList())
                platformClasspath.from(file("platform.jar"))
                graph.set(listOf("root\tproject::sdk", "node\tproject::sdk\t1"))
                declarations.set(emptyList())
            }
        """.trimIndent())
        archive(File(directory, "sdk.aar"), mapOf(
            "AndroidManifest.xml" to "<manifest/>".toByteArray(),
            "classes.jar" to zipBytes(mapOf("sdk/Api.class" to classBytes("sdk/Api", "outside/Base"))),
        ))
        archive(File(directory, "outside.jar"), mapOf("outside/Base.class" to classBytes("outside/Base")))
        archive(File(directory, "platform.jar"), mapOf("java/lang/Object.class" to classBytes("java/lang/Object")))
        fun runner(vararg args: String) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("verifyAbi", "--configuration-cache", "--configuration-cache-problems=fail", "--build-cache", "--stacktrace", *args)
        assertTrue(runner().buildAndFail().output.contains("FATAAR_API_DEPENDENCY: sdk/Api exposes outside/Base"))
        assertEquals(TaskOutcome.SUCCESS, runner("-PexposeApi=true").build().task(":verifyAbi")!!.outcome)
        assertArrayEquals(File(directory, "sdk.aar").readBytes(), File(directory, "build/sdk.aar").readBytes())
        val repeated = runner("-PexposeApi=true").build()
        assertTrue(repeated.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":verifyAbi")!!.outcome)
        File(directory, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, runner("-PexposeApi=true").build().task(":verifyAbi")!!.outcome)
        archive(File(directory, "outside.jar"), mapOf("outside/Changed.class" to classBytes("outside/Changed")))
        assertTrue(runner("-PexposeApi=true").buildAndFail().output.contains("FATAAR_MISSING_API_CLASS"))
    }
}
internal fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
    val bytes = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(java.util.zip.ZipEntry(name).apply { time = 0 })
            zip.write(content); zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}
