package com.fataar.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.create
import org.gradle.api.component.SoftwareComponentFactory
import javax.inject.Inject

/** Dependency accounting, JVM API checks, and a cacheable AAR merge with explicit outgoing variants. */
class FatAarPlugin @Inject constructor(private val componentFactory: SoftwareComponentFactory) : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<FatAarExtension>("fatAar")
        extension.sourcesJarEnabled.convention(false)
        extension.resourceWinners.convention(emptyMap())
        extension.autoEmbedProjectPrefixes.convention(emptyList())
        extension.prefabPackageName.convention(project.name)
        extension.nativeAbis.convention(emptyList())
        project.configurations.dependencyScope("embed")
        val wiring = DependencyValidationWiring(project, extension)
        project.pluginManager.withPlugin("java-library") {
            wiring.validateJvmScopes()
            wiring.prepare()
            val validation = wiring.register("", project.configurations.getByName("runtimeClasspath"))
            project.tasks.named("check") { dependsOn(validation) }
            project.tasks.named("jar") { dependsOn(validation) }
        }
        project.pluginManager.withPlugin("com.android.library") {
            wiring.prepare()
            wiring.prepareAndroidScopes()
            val android = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            val aggregate = project.tasks.register("validateDependencies") { group = "verification" }
            // One chosen variant per invocation; e.g. -PfatAar.variant=demoRelease for flavors.
            project.tasks.register("addMissingDependencies") {
                group = "fat aar"
                dependsOn(project.providers.gradleProperty("fatAar.variant").orElse("release").map {
                    "addMissing" + it.replaceFirstChar { character -> character.uppercaseChar() } + "Dependencies"
                })
            }
            android.onVariants(android.selector().all()) { variant ->
                val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
                wiring.prepareVariantScope(variant.name, variant.compileConfiguration, variant.runtimeConfiguration)
                val validation = wiring.register(suffix, variant.runtimeConfiguration, variant.name + "Embed")
                variant.lifecycleTasks.registerPreBuild(validation)
                aggregate.configure { dependsOn(validation) }
                val abi = wiring.registerAbi(suffix, variant.runtimeConfiguration, variant.compileConfiguration,
                    android.sdkComponents.bootClasspath, validation)
                variant.artifacts.use(abi).wiredWithFiles(ValidateAbiTask::inputAar, ValidateAbiTask::outputAar)
                    .toTransform(SingleArtifact.AAR)
                MergeWiring(project, componentFactory).register(variant, validation, android.sdkComponents.bootClasspath)
                project.tasks.register<InspectAarTask>(
                    "inspect" + variant.name.replaceFirstChar { it.uppercaseChar() } + "Aar"
                ) {
                    group = "verification"
                    description = "Inventories the final merged " + variant.name + " AAR."
                    aarFile.set(variant.artifacts.get(SingleArtifact.AAR))
                    reportFile.set(project.layout.buildDirectory.file(
                        "reports/fat-aar/" + variant.name + "/inventory.txt"
                    ))
                }
            }
        }
    }
}
