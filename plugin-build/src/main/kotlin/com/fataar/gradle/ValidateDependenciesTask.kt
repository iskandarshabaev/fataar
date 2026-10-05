package com.fataar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class ValidateDependenciesTask : DefaultTask() {
    @get:Input abstract val fixScope: Property<String>
    @get:Input abstract val graph: ListProperty<String>
    @get:Input abstract val declarations: ListProperty<String>
    @get:Input abstract val autoEmbedProjectPrefixes: ListProperty<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    init { fixScope.convention("embed") }

    @TaskAction fun validate() {
        val result = DependencyValidator.analyze(graph.get(), declarations.get(), autoEmbedProjectPrefixes.get(), fixScope.get())
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText(result.report) }
        if (result.issues.isNotEmpty()) throw GradleException(result.issues.joinToString("\n") { it.code + ": " + it.message })
    }
}
