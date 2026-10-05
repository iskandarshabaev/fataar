package com.fataar.gradle

import java.io.File
import java.io.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*

data class DependencyArtifact(
    @get:Input val owner: String,
    @get:Classpath val file: File,
) : Serializable

/** An identity AAR transform: consumers and publications cannot bypass JVM ABI validation. */
@CacheableTask
abstract class ValidateAbiTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val inputAar: RegularFileProperty
    @get:OutputFile abstract val outputAar: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty
    @get:Nested abstract val runtimeArtifacts: ListProperty<DependencyArtifact>
    @get:Nested abstract val apiArtifacts: ListProperty<DependencyArtifact>
    @get:Classpath abstract val platformClasspath: ConfigurableFileCollection
    @get:Input abstract val graph: ListProperty<String>
    @get:Input abstract val declarations: ListProperty<String>

    @TaskAction fun validate() {
        val entries = graph.get().map { it.split('\t') }
        val root = entries.single { it[0] == "root" }[1]
        val embedRequests = declarations.get().map { it.split('\t') }.filter { it[0] == "embed" }.map { it[1] }.toSet()
        val embedded = entries.filter { it[0] == "edge" && it[1] == root && it[3] in embedRequests }.map { it[2] }.toSet()
        val runtime = runtimeArtifacts.get()
        val unsupported = runtime.filter { it.owner.startsWith("unsupported:") }
        if (unsupported.isNotEmpty()) throw GradleException("FATAAR_UNSUPPORTED_FILE_DEPENDENCY: " +
            unsupported.joinToString { it.file.name } + ". File dependencies need an explicit packaging contract.")
        val owners = mutableMapOf<String, String>()
        val packaged = mutableListOf<AbiClass>()
        val issues = sortedSetOf<String>()
        fun packaged(owner: String, file: File) {
            JvmAbi.classes(file).map(JvmAbi::read).forEach { type ->
                val previous = owners.putIfAbsent(type.name, owner)
                if (previous != null) issues += "FATAAR_DUPLICATE_CLASS: ${type.name} in $previous and $owner"
                packaged += type
            }
        }
        packaged("sdk", inputAar.get().asFile)
        runtime.filter { it.owner in embedded }.forEach { packaged(it.owner, it.file) }
        val externalOwners = mutableMapOf<String, String>()
        runtime.filter { it.owner !in embedded }.forEach { artifact ->
            JvmAbi.classes(artifact.file).forEach { bytes -> externalOwners.putIfAbsent(org.objectweb.asm.ClassReader(bytes).className, artifact.owner) }
        }
        val consumerGenerated = ConsumerGeneratedTypes.dataBinding(inputAar.get().asFile, owners.keys)
        val available = (owners.keys + consumerGenerated).toMutableSet()
        val availableBytecode = mutableMapOf<String, ByteArray>()
        (apiArtifacts.get().map { it.file } + platformClasspath.files).forEach { file ->
            JvmAbi.classes(file).forEach {
                val name = org.objectweb.asm.ClassReader(it).className
                available.add(name)
                availableBytecode.putIfAbsent(name, it)
            }
        }
        val inherited = JvmAbi.inheritedApi(packaged, availableBytecode)
        JvmAbi.missing(packaged + inherited, available).forEach { (origin, type) ->
            val owner = externalOwners[type]
            issues += if (owner != null) "FATAAR_API_DEPENDENCY: $origin exposes $type from $owner; declare api(...) in SDK."
                else "FATAAR_MISSING_API_CLASS: $origin exposes $type, absent from packaged classes, external API and platform."
        }
        val report = buildString {
            appendLine("format=1")
            appendLine("coverage=jvm-public-protected-signatures")
            appendLine("stage=pre-merge-api-validation")
            appendLine("classes=" + packaged.size)
            consumerGenerated.sorted().forEach { appendLine("consumer-generated=$it") }
            issues.forEach(::appendLine)
            appendLine("result=" + if (issues.isEmpty()) "PASS" else "FAIL")
        }
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText(report) }
        if (issues.isNotEmpty()) throw GradleException(issues.joinToString("\n"))
        outputAar.get().asFile.apply { parentFile.mkdirs(); inputAar.get().asFile.copyTo(this, overwrite = true) }
    }
}
