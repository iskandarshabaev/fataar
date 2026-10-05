package com.fataar.gradle

import java.io.File
import java.util.zip.ZipFile

/** AGP excludes this interface from library AARs and generates it in the consuming app. */
internal object ConsumerGeneratedTypes {
    fun linkage(aar: File, packagedClasses: Set<String>): Set<String> = dataBinding(aar, packagedClasses) + ZipFile(aar).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("data-binding/") && it.endsWith("-br.bin") }
            .map { it.removePrefix("data-binding/").removeSuffix("-br.bin").replace('.', '/') }
            .filter { "$it/DataBinderMapperImpl" in packagedClasses }
            .map { "$it/BR" }.toSet()
    }
    fun dataBinding(aar: File, packagedClasses: Set<String>): Set<String> = ZipFile(aar).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toSet()
        val packages = names.filter { it.startsWith("data-binding/") && it.endsWith("-br.bin") }
            .map { it.removePrefix("data-binding/").removeSuffix("-br.bin") }
        if (packages.any { namespace ->
                "data-binding-base-class-log/$namespace-binding_classes.json" in names &&
                    namespace.replace('.', '/') + "/DataBinderMapperImpl" in packagedClasses
            }) setOf("androidx/databinding/DataBindingComponent") else emptySet()
    }
}
