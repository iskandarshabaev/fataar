package com.fataar.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")
        val moduleNamespace = "com.fataar.fixtures." + project.name.replace("-", "")
        project.extensions.configure<LibraryExtension> {
            namespace = moduleNamespace
            compileSdk = 36
            defaultConfig {
                minSdk = 24
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            publishing.singleVariant("release") { withSourcesJar() }
        }
        project.publishBaseline("release")
        project.pluginManager.apply("com.fataar.embeddable")
    }
}
