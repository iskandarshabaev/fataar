package com.fataar.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MergeCacheIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun cachesRelocatesAndInvalidatesEmbeddedPayload() {
        val directory = temporary.newFolder("original")
        val cache = temporary.newFolder("cache")
        File(directory, "settings.gradle.kts").writeText("rootProject.name = \"merge-cache\"\nbuildCache { local { directory = file(\"${cache.invariantSeparatorsPath}\") } }")
        File(directory, "gradle.properties").writeText("org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx512m\n")
        File(directory, "build.gradle.kts").writeText("""
            import com.fataar.gradle.MergeAarTask
            import com.fataar.gradle.MergeInput
            plugins { id("com.fataar") }
            tasks.register<MergeAarTask>("merge") {
                inputAar.set(layout.projectDirectory.file("sdk.aar"))
                embedded.set(listOf(MergeInput("child", file("child.aar"))))
                expectedOwners.set(listOf("child"))
                outputAar.set(layout.buildDirectory.file("sdk.aar"))
                reportFile.set(layout.buildDirectory.file("merge.txt"))
            }
        """.trimIndent())
        fun fixture(path: File, namespace: String, value: String) = archive(path, mapOf(
            "AndroidManifest.xml" to "<manifest package=\"$namespace\"/>".toByteArray(),
            "assets/$namespace.txt" to value.toByteArray()))
        fixture(File(directory, "sdk.aar"), "sdk", "sdk")
        fixture(File(directory, "child.aar"), "child", "first")
        fun runner(root: File) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("merge", "--configuration-cache", "--configuration-cache-problems=fail", "--build-cache", "--stacktrace")
        assertEquals(TaskOutcome.SUCCESS, runner(directory).build().task(":merge")!!.outcome)
        val bytes = File(directory, "build/sdk.aar").readBytes()
        val repeated = runner(directory).build()
        assertTrue(repeated.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":merge")!!.outcome)
        File(directory, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, runner(directory).build().task(":merge")!!.outcome)
        val relocated = temporary.newFolder("relocated")
        directory.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(relocated, it.name)) }
        assertEquals(TaskOutcome.FROM_CACHE, runner(relocated).build().task(":merge")!!.outcome)
        assertArrayEquals(bytes, File(relocated, "build/sdk.aar").readBytes())
        fixture(File(relocated, "child.aar"), "child", "changed")
        assertEquals(TaskOutcome.SUCCESS, runner(relocated).build().task(":merge")!!.outcome)
        assertEquals("changed", AarMerger.readZip(File(relocated, "build/sdk.aar").readBytes(), "output").getValue("assets/child.txt").decodeToString())
    }
}
