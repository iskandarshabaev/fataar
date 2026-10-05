package com.fataar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader

@CacheableTask
abstract class ValidateLinkageTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputAar: RegularFileProperty
    @get:OutputFile abstract val outputAar: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty
    @get:Nested abstract val externalRuntime: ListProperty<MergeInput>
    @get:Classpath abstract val platformClasspath: ConfigurableFileCollection
    @TaskAction fun validate() {
        val packaged = JvmAbi.classes(inputAar.get().asFile)
        val available = linkedMapOf<String, ByteArray>()
        fun add(bytes: ByteArray) { available.putIfAbsent(ClassReader(bytes).className, bytes) }
        packaged.forEach(::add)
        externalRuntime.get().forEach { artifact ->
            JvmAbi.classes(artifact.file).forEach(::add)
            if (artifact.file.extension == "aar") {
                val entries = AarMerger.readZip(artifact.file.readBytes(), artifact.owner)
                val namespace = AarMerger.xml(entries.getValue("AndroidManifest.xml")).getAttribute("package")
                if (namespace.isNotBlank()) CompileResourceStubs.generate(namespace, entries["R.txt"]?.decodeToString().orEmpty()).values.forEach(::add)
            }
        }
        platformClasspath.files.forEach { JvmAbi.classes(it).forEach(::add) }
        val entries = AarMerger.readZip(inputAar.get().asFile.readBytes(), "sdk")
        val namespace = AarMerger.xml(entries.getValue("AndroidManifest.xml")).getAttribute("package")
        CompileResourceStubs.generate(namespace, entries["R.txt"]?.decodeToString().orEmpty()).values.forEach(::add)
        val deferred = ConsumerGeneratedTypes.linkage(inputAar.get().asFile, available.keys) +
            externalRuntime.get().filter { it.file.extension == "aar" }.flatMap { ConsumerGeneratedTypes.linkage(it.file, available.keys) }
        val issues = JvmLinkage.validate(packaged, available, deferred)
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("format=1\nstage=final-aar\ncoverage=bytecode-class-method-field-static-and-member-access\nclasses=${packaged.size}\n" +
                "deferred-consumer-generated=${deferred.sorted()}\n" + issues.joinToString("\n", postfix = "\n") + "result=${if (issues.isEmpty()) "PASS" else "FAIL"}\n")
        }
        if (issues.isNotEmpty()) throw GradleException(issues.joinToString("\n") + "\nSelected external runtime: " +
            externalRuntime.get().joinToString { "${it.owner}/${it.file.name}" })
        outputAar.get().asFile.apply { parentFile.mkdirs(); inputAar.get().asFile.copyTo(this, overwrite = true) }
    }
}
