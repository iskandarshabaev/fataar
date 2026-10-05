package com.fataar.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourcesCacheIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun generatedSourceTaskDependenciesCacheRelocationAndDeletionWork() {
        val directory = temporary.newFolder("original")
        val cache = temporary.newFolder("cache")
        File(directory, "settings.gradle.kts").writeText("rootProject.name=\"sources-cache\"\nbuildCache { local { directory=file(\"${cache.invariantSeparatorsPath}\") } }")
        File(directory, "gradle.properties").writeText("org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx512m\n")
        File(directory, "src/pkg").mkdirs()
        val java = File(directory, "src/pkg/Code.java").also { it.writeText("package pkg; class Code {}") }
        File(directory, "src/pkg/Removed.java").writeText("package pkg; class Removed {}")
        File(directory, "build.gradle.kts").writeText("""
            import com.fataar.gradle.ExportSourcesTask
            import com.fataar.gradle.MergeSourcesTask
            import com.fataar.gradle.MergeInput
            plugins { id("com.fataar") }
            val export = tasks.register<ExportSourcesTask>("exportSources") {
                sourceRoots.from(layout.projectDirectory.dir("src"), layout.projectDirectory.dir("missing"))
                owner.set("child"); variantName.set("release"); moduleVersion.set("1")
                sourcesJar.set(layout.buildDirectory.file("own.jar"))
            }
            tasks.register<MergeSourcesTask>("mergeSources") {
                source("child", export.flatMap { it.sourcesJar })
                expectedOwners.set(listOf("child"))
                sourcesJar.set(layout.buildDirectory.file("sources.jar"))
                reportFile.set(layout.buildDirectory.file("sources.txt"))
            }
        """.trimIndent())
        fun runner(root: File) = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("mergeSources", "--configuration-cache", "--configuration-cache-problems=fail", "--build-cache", "--stacktrace")
        val first = runner(directory).build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":exportSources")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":mergeSources")!!.outcome)
        val bytes = File(directory, "build/sources.jar").readBytes()
        val repeated = runner(directory).build()
        assertTrue(repeated.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":mergeSources")!!.outcome)
        File(directory, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, runner(directory).build().task(":mergeSources")!!.outcome)
        val relocated = temporary.newFolder("relocated")
        directory.listFiles()!!.filter { it.name != "build" && it.name != ".gradle" }.forEach {
            it.copyRecursively(File(relocated, it.name), overwrite = true)
        }
        assertEquals(TaskOutcome.FROM_CACHE, runner(relocated).build().task(":mergeSources")!!.outcome)
        assertArrayEquals(bytes, File(relocated, "build/sources.jar").readBytes())
        java.writeText("package pkg; class Code { int changed; }")
        File(directory, "src/pkg/Removed.java").delete()
        val changed = runner(directory).build()
        assertTrue(changed.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.SUCCESS, changed.task(":mergeSources")!!.outcome)
        val output = AarMerger.readZip(File(directory, "build/sources.jar").readBytes(), "output")
        assertEquals("package pkg; class Code { int changed; }", output.getValue("pkg/Code.java").decodeToString())
        assertFalse(output.containsKey("pkg/Removed.java"))
    }
}
