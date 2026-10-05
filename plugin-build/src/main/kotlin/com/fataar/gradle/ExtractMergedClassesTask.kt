package com.fataar.gradle

import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.XMLConstants
import org.gradle.api.provider.Property
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/** Secondary JAR for AGP's project-dependency dexing, always derived from the final merged AAR. */
@CacheableTask
abstract class ExtractMergedClassesTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aar: RegularFileProperty
    @get:OutputFile abstract val classesJar: RegularFileProperty
    @get:Input abstract val includeResourceStubs: Property<Boolean>
    init { includeResourceStubs.convention(false) }

    @TaskAction fun extract() {
        ZipFile(aar.get().asFile).use { archive ->
            val entry = archive.getEntry("classes.jar") ?: throw GradleException("FATAAR_INVALID_AAR: missing classes.jar")
            val output = classesJar.get().asFile
            output.parentFile.mkdirs()
            val bytes = archive.getInputStream(entry).use { it.readBytes() }
            if (!includeResourceStubs.get()) output.writeBytes(bytes) else {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                }
                val namespace = archive.getInputStream(archive.getEntry("AndroidManifest.xml")).use {
                    factory.newDocumentBuilder().parse(it).documentElement.getAttribute("package")
                }
                val symbols = archive.getInputStream(archive.getEntry("R.txt")).use { it.readBytes().decodeToString() }
                val classes = AarMerger.readZip(bytes, "classes.jar")
                output.writeBytes(AarMerger.zip(classes + CompileResourceStubs.generate(namespace, symbols)))
            }
        }
    }
}
