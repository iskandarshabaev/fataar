package com.fataar.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DependencyValidationIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun androidScopedRegistryIsRejectedOnJvmRatherThanIgnored() {
        val directory = project()
        File(directory, "fat-aar-dependencies.txt").writeText("redDebugEmbed project :internal:leaf\n")
        val failure = runner(directory, "validateDependencies").buildAndFail()
        assertTrue(failure.output, failure.output.contains("FATAAR_INVALID_RULE_SCOPE: redDebugEmbed"))
        assertTrue(failure.output.contains("JVM libraries use embed"))
    }

    @Test fun missingTransitiveFailsFixIsIdempotentAndBothCachesSurvive() {
        val directory = project()
        val failure = runner(directory, "validateDependencies").buildAndFail()
        assertTrue(failure.output.contains("FATAAR_MISSING_DEPENDENCY"))
        assertTrue(failure.output, failure.output.contains("project:: -> project::feature -> project::internal:leaf"))
        val cachedFailure = runner(directory, "validateDependencies").buildAndFail()
        assertTrue(cachedFailure.output.contains("Reusing configuration cache."))
        runner(directory, "addMissingDependencies").build()
        val registry = File(directory, "fat-aar-dependencies.txt")
        val saved = registry.readText()
        assertTrue(saved.contains("embed project :internal:leaf"))
        assertEquals(TaskOutcome.SUCCESS, runner(directory, "validateDependencies").build().task(":validateDependencies")!!.outcome)
        val cached = runner(directory, "validateDependencies").build()
        assertTrue(cached.output.contains("Reusing configuration cache."))
        assertEquals(TaskOutcome.UP_TO_DATE, cached.task(":validateDependencies")!!.outcome)
        File(directory, "build/reports").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, runner(directory, "validateDependencies").build().task(":validateDependencies")!!.outcome)
        runner(directory, "addMissingDependencies").build()
        assertEquals(saved, registry.readText())

        // A new child declaration must invalidate the cache and fail, even without changing SDK sources.
        File(directory, "feature/build.gradle.kts").appendText("\ndependencies { implementation(project(\":internal:newleaf\")) }\n")
        val changed = runner(directory, "jar").buildAndFail()
        assertTrue(changed.output.contains("FATAAR_MISSING_DEPENDENCY: project::internal:newleaf"))
    }

    @Test fun unknownExternalBlocksAutofixWithoutPartialWrites() {
        val directory = project()
        publishPom(directory, "remote", "")
        File(directory, "feature/build.gradle.kts").appendText("\ndependencies { implementation(\"test:remote:1\") }\n")
        val failure = runner(directory, "addMissingDependencies").buildAndFail()
        assertTrue(failure.output.contains("FATAAR_FIX_REQUIRES_DECISION"))
        assertTrue(failure.output.contains("module:test:remote"))
        assertFalse(File(directory, "fat-aar-dependencies.txt").exists())
        File(directory, "build.gradle.kts").appendText("\ndependencies { implementation(\"test:remote:1\") }\n")
        runner(directory, "addMissingDependencies").build()
        runner(directory, "validateDependencies").build()
    }

    @Test fun unresolvedDependencyHasPathAndDoesNotWriteRegistry() {
        val directory = project()
        File(directory, "feature/build.gradle.kts").appendText("\ndependencies { implementation(\"test:absent:1\") }\n")
        val failure = runner(directory, "validateDependencies").buildAndFail()
        assertTrue(failure.output.contains("FATAAR_UNRESOLVED_DEPENDENCY"))
        assertTrue(failure.output.contains("test:absent:1"))
        assertFalse(File(directory, "fat-aar-dependencies.txt").exists())
    }

    @Test fun externalTransitivesAreCoveredButConstraintAloneDoesNotCoverThem() {
        val directory = project()
        publishPom(directory, "child", "")
        publishPom(directory, "remote", "<dependencies><dependency><groupId>test</groupId><artifactId>child</artifactId><version>1</version></dependency></dependencies>")
        File(directory, "feature/build.gradle.kts").appendText("\ndependencies { implementation(\"test:remote:1\") }\n")
        File(directory, "build.gradle.kts").appendText("\ndependencies { constraints { implementation(\"test:remote:1\") } }\n")
        assertTrue(runner(directory, "validateDependencies").buildAndFail().output.contains("FATAAR_MISSING_DEPENDENCY: module:test:remote"))
        File(directory, "build.gradle.kts").appendText("\ndependencies { api(\"test:remote:1\") }\n")
        runner(directory, "addMissingDependencies").build()
        runner(directory, "validateDependencies").build()
    }

    private fun runner(directory: File, vararg tasks: String) = GradleRunner.create().withProjectDir(directory)
        .withPluginClasspath().withArguments(*tasks, "--configuration-cache", "--configuration-cache-problems=fail",
            "--build-cache", "--console=plain", "--stacktrace")

    private fun project(): File = temporary.newFolder().apply {
        File(this, "settings.gradle.kts").writeText("""
            rootProject.name = "sdk"
            include(":feature", ":internal:leaf", ":internal:newleaf")
            dependencyResolutionManagement { repositories { maven { url = uri("repo") } } }
            buildCache { local { directory = file("cache") } }
        """.trimIndent())
        File(this, "gradle.properties").writeText("org.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx512m\n")
        File(this, "build.gradle.kts").writeText("""
            plugins { `java-library`; id("com.fataar") }
            fatAar { autoEmbedProjectPrefixes.add(":internal:") }
            dependencies { "embed"(project(":feature")) }
        """.trimIndent())
        listOf("feature", "internal/leaf", "internal/newleaf").forEach { module ->
            File(this, module).mkdirs()
            File(this, "$module/build.gradle.kts").writeText("plugins { `java-library` }\n")
        }
        File(this, "feature/build.gradle.kts").appendText("dependencies { implementation(project(\":internal:leaf\")) }\n")
        File(this, "repo").mkdirs()
    }
    private fun publishPom(directory: File, name: String, dependencies: String) {
        val module = File(directory, "repo/test/$name/1").apply { mkdirs() }
        File(module, "$name-1.pom").writeText("""
            <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>$name</artifactId><version>1</version>$dependencies</project>
        """.trimIndent())
        archive(File(module, "$name-1.jar"), emptyMap())
    }
}
