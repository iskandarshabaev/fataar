package com.fataar.gradle

import java.io.File
import java.io.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*

data class MergeInput(
    @get:Input val owner: String,
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val file: File,
) : Serializable

@CacheableTask
abstract class MergeAarTask : DefaultTask() {
    @get:Input abstract val resourceWinners: org.gradle.api.provider.MapProperty<String, String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputAar: RegularFileProperty
    @get:Input abstract val prefabPackageName: org.gradle.api.provider.Property<String>
    @get:Input abstract val nativeAbis: ListProperty<String>
    @get:Nested abstract val externalRuntime: ListProperty<MergeInput>
    @get:Nested abstract val externalApi: ListProperty<MergeInput>
    @get:Nested abstract val embedded: ListProperty<MergeInput>
    @get:Input abstract val expectedOwners: ListProperty<String>
    @get:OutputFile abstract val outputAar: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    init { resourceWinners.convention(emptyMap()); externalApi.convention(emptyList()); externalRuntime.convention(emptyList()); nativeAbis.convention(emptyList()); prefabPackageName.convention("sdk") }

    @TaskAction fun merge() {
        val missing = expectedOwners.get().toSet() - embedded.get().map { it.owner }.toSet()
        if (missing.isNotEmpty()) throw org.gradle.api.GradleException(
            "FATAAR_MISSING_EMBED_ARTIFACT: no AAR/JAR for ${missing.sorted()}")
        val report = AarMerger.merge(inputAar.get().asFile, embedded.get(), outputAar.get().asFile, temporaryDir, externalApi.get(), prefabPackageName.get(), nativeAbis.get().toSet(), externalRuntime.get(), resourceWinners.get())
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText(report) }
    }
}
