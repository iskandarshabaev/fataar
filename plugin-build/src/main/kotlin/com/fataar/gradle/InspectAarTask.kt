package com.fataar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class InspectAarTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aarFile: RegularFileProperty
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun inspect() {
        val report = AarInventory.inspect(aarFile.get().asFile)
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(report, Charsets.UTF_8)
    }
}
