package com.fataar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class ExportSourcesTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories
    abstract val sourceRoots: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories
    abstract val staticSourceRoots: ConfigurableFileCollection
    @get:Input abstract val owner: Property<String>
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val moduleVersion: Property<String>
    @get:Input val dataBinding: Property<Boolean> = project.objects.property(Boolean::class.java).convention(false)
    @get:OutputFile abstract val sourcesJar: RegularFileProperty

    @TaskAction fun export() = SourcesArchive.export(sourceRoots.files, owner.get(), variantName.get(),
        moduleVersion.get(), sourcesJar.get().asFile, staticSourceRoots.files, dataBinding.get())
}

@CacheableTask
abstract class MergeSourcesTask : DefaultTask() {
    fun source(owner: String, jar: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>) {
        dependsOn(jar)
        sourceArchives.add(jar.map { MergeInput(owner, it.asFile) })
    }

    @get:Nested abstract val sourceArchives: ListProperty<MergeInput>
    @get:Input abstract val expectedOwners: ListProperty<String>
    @get:OutputFile abstract val sourcesJar: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun merge() = SourcesArchive.merge(sourceArchives.get(), expectedOwners.get().toSet(),
        sourcesJar.get().asFile, reportFile.get().asFile)
}
