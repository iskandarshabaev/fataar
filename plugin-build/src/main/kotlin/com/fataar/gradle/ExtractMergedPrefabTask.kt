package com.fataar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/** A project consumer must configure CMake only after the full SDK Prefab package exists. */
@CacheableTask
abstract class ExtractMergedPrefabTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aar: RegularFileProperty
    @get:OutputFile abstract val configurationFingerprint: RegularFileProperty
    @TaskAction fun extract() {
        val output = configurationFingerprint.get().asFile
        output.parentFile.mkdirs()
        val entries = AarMerger.readZip(aar.get().asFile.readBytes(), "SDK Prefab")
        // AGP extracts the actual package from the AAR. This file is only its
        // configure fingerprint/build dependency; no synthetic package is exported.
        output.writeBytes(entries["prefab/prefab.json"] ?: "{}".toByteArray())
    }
}
