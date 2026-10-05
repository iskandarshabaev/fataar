package com.fataar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@DisableCachingByDefault(because = "Explicit source-model mutation, never a build-cache output")
abstract class AddMissingDependenciesTask : DefaultTask() {
    @get:Input abstract val fixScope: Property<String>
    @get:Input abstract val graph: ListProperty<String>
    @get:Input abstract val declarations: ListProperty<String>
    @get:Input abstract val autoEmbedProjectPrefixes: ListProperty<String>
    @get:Internal abstract val rulesFile: RegularFileProperty

    init { fixScope.convention("embed"); doNotTrackState("Writes dependency declarations for the next invocation, never the current graph") }

    @TaskAction fun addMissing() {
        val result = DependencyValidator.analyze(graph.get(), declarations.get(), autoEmbedProjectPrefixes.get(), fixScope.get())
        val ambiguous = result.issues.filter { it.fix == null }
        if (ambiguous.isNotEmpty()) throw GradleException(
            "FATAAR_FIX_REQUIRES_DECISION: no files changed.\n" +
                ambiguous.joinToString("\n") { it.code + ": " + it.message })
        val fixes = result.issues.mapNotNull { it.fix }.distinct().sorted()
        if (fixes.isEmpty()) { logger.lifecycle("No missing dependencies."); return }
        val target = rulesFile.get().asFile.toPath()
        val existing = if (Files.exists(target)) Files.readString(target) else "# Explicit fat-AAR dependency declarations.\n"
        DependencyRules.parse(existing)
        val lines = existing.lineSequence().map { it.trim() }.toSet()
        val additions = fixes.filter { it !in lines }
        if (additions.isEmpty()) return
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".fat-aar-dependencies-", ".tmp")
        try {
            Files.writeString(temporary, existing.trimEnd() + "\n" + additions.joinToString("\n", postfix = "\n"))
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        logger.lifecycle("Added to ${target.fileName}:\n" + additions.joinToString("\n") +
            "\nRun the build again; the current invocation still uses the original graph.")
    }
}
