package com.fataar.gradle

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

internal fun Project.publishBaseline(componentName: String) {
    pluginManager.apply("maven-publish")
    group = "com.fataar.playground"
    version = "0.1.0-baseline"
    val destination = rootProject.layout.projectDirectory.dir(".playground-repository")
    val moduleName = name
    extensions.configure<PublishingExtension> {
        repositories.maven {
            name = "Playground"
            url = destination.asFile.toURI()
        }
    }
    val publishing = extensions.getByType(PublishingExtension::class.java)
    components.configureEach {
        val component = this
        if (name == componentName) {
            publishing.publications.create<MavenPublication>("baseline") {
                from(component)
                pom {
                    name.set(moduleName)
                    description.set("Modular validation baseline; not a merged fat AAR.")
                }
            }
        }
    }
}
