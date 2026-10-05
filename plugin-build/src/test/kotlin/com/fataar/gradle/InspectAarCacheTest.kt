package com.fataar.gradle
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InspectAarCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun reusesConfigurationAndBuildCacheAndInvalidatesOnInputChange() {
        val cache = temporary.newFolder("cache")
        val first = project("first", cache)
        fun run(directory: File) = GradleRunner.create().withProjectDir(directory)
            .withPluginClasspath()
            .withArguments("inspectFixture", "--configuration-cache", "--configuration-cache-problems=fail",
                "--build-cache", "--console=plain", "--stacktrace").build()
        val initial = run(first)
        assertEquals(TaskOutcome.SUCCESS, initial.task(":inspectFixture")!!.outcome)
        val expected = File(first, "build/inventory.txt").readText()
        val repeated = run(first)
        assertTrue(repeated.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":inspectFixture")!!.outcome)
        File(first, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, run(first).task(":inspectFixture")!!.outcome)
        assertEquals(expected, File(first, "build/inventory.txt").readText())
        val relocated = project("relocated", cache)
        assertEquals(TaskOutcome.FROM_CACHE, run(relocated).task(":inspectFixture")!!.outcome)
        assertEquals(expected, File(relocated, "build/inventory.txt").readText())
        archive(File(first, "input.aar"), mapOf(
            "AndroidManifest.xml" to "<manifest/>".toByteArray(),
            "classes.jar" to jar("changed/NewClass.class"),
        ))
        assertEquals(TaskOutcome.SUCCESS, run(first).task(":inspectFixture")!!.outcome)
        assertTrue(File(first, "build/inventory.txt").readText().contains("changed/NewClass.class"))
    }
    private fun project(name: String, cache: File): File = temporary.newFolder(name).apply {
        File(this, "settings.gradle.kts").writeText("""
            rootProject.name = "inventory-test"
            enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
            buildCache { local { directory = file("${cache.invariantSeparatorsPath}") } }
        """.trimIndent())
        File(this, "gradle.properties").writeText("org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx512m\n")
        File(this, "build.gradle.kts").writeText("""
            import com.fataar.gradle.InspectAarTask
            plugins { id("com.fataar") }
            tasks.register<InspectAarTask>("inspectFixture") {
                aarFile.set(layout.projectDirectory.file("input.aar"))
                reportFile.set(layout.buildDirectory.file("inventory.txt"))
            }
        """.trimIndent())
        archive(File(this, "input.aar"), mapOf(
            "AndroidManifest.xml" to "<manifest/>".toByteArray(),
            "classes.jar" to jar("example/Original.class"),
        ))
    }
}
