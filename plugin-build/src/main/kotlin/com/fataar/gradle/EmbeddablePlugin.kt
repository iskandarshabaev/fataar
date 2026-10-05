package com.fataar.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute

internal fun inputCapability(path: String): String = "com.fataar.input:" + path.removePrefix(":").replace(':', '.')

/** Publishes a complete variant AAR through public APIs, without inspecting another project's tasks. */
class EmbeddablePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.library") {
            val android = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            android.onVariants(android.selector().all()) { variant ->
                project.configurations.consumable("fatAarInput" + variant.name.replaceFirstChar { it.uppercaseChar() }) {
                    variant.runtimeConfiguration.attributes.keySet().forEach { key ->
                        @Suppress("UNCHECKED_CAST")
                        attributes.attribute(key as Attribute<Any>, variant.runtimeConfiguration.attributes.getAttribute(key)!!)
                    }
                    outgoing.capability(inputCapability(project.path) + ":1")
                    outgoing.artifact(variant.artifacts.get(SingleArtifact.AAR)) { type = "aar"; extension = "aar" }
                }
                val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
                val sources = registerAndroidSources(project, variant, "export${suffix}FatAarSources")
                project.configurations.consumable("fatAarInput${suffix}Sources") {
                    sourcesAttributes(project, this, variant, false)
                    outgoing.capability(inputCapability(project.path) + ":1")
                    outgoing.artifact(sources.flatMap { it.sourcesJar }) { type = "jar"; extension = "jar"; classifier = "sources" }
                }
            }
        }
        // Java modules expose their ordinary runtime JAR using the same explicit capability.
        project.pluginManager.withPlugin("java-library") {
            project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension::class.java).withSourcesJar()
            project.tasks.named("sourcesJar", org.gradle.api.tasks.bundling.Jar::class.java) {
                isReproducibleFileOrder = true
                isPreserveFileTimestamps = false
            }
            project.configurations.named("runtimeElements") {
                outgoing.capability("${project.group}:${project.name}:${project.version}")
                outgoing.capability(inputCapability(project.path) + ":1")
            }
            project.configurations.named("sourcesElements") {
                outgoing.capability("${project.group}:${project.name}:${project.version}")
                outgoing.capability(inputCapability(project.path) + ":1")
            }
        }
    }
}
