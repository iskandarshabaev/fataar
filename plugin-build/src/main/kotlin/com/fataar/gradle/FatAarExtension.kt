package com.fataar.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class FatAarExtension {
    /** Resource type/name or qualifier/type/name -> sdk, project::path or module:group:name. */
    abstract val resourceWinners: org.gradle.api.provider.MapProperty<String, String>
    abstract val sourcesJarEnabled: Property<Boolean>
    /** Attach a merged sources artifact to fat components; building it requires all embedded sources. */
    fun withSourcesJar() { sourcesJarEnabled.set(true) }
    abstract val prefabPackageName: Property<String>
    abstract val nativeAbis: ListProperty<String>
    /** Only these project path prefixes are eligible for automatic embed declarations. */
    abstract val autoEmbedProjectPrefixes: ListProperty<String>
}
