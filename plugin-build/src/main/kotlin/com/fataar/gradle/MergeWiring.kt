package com.fataar.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.*
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun archiveIdentity(id: ComponentIdentifier): String = when (id) {
        is ProjectComponentIdentifier -> "project:" + id.projectPath
        is ModuleComponentIdentifier -> "module:" + id.group + ":" + id.module
        else -> error("FATAAR_UNSUPPORTED_COMPONENT: $id")
    }

internal class MergeWiring(private val project: Project, private val components: SoftwareComponentFactory) {
    fun register(variant: LibraryVariant, check: TaskProvider<ValidateDependenciesTask>, platform: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>) {
        val fatAarExtension = project.extensions.getByType(FatAarExtension::class.java)
        val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
        val embedScopes = variant.runtimeConfiguration.hierarchy.filter { isEmbedScope(it.name) }
        val embeddedKeys = project.provider {
            embedScopes.flatMap { it.dependencies }.map {
                when (it) {
                    is ProjectDependency -> "project:" + it.path
                    is ExternalModuleDependency -> "module:" + it.group + ":" + it.name
                    else -> error("FATAAR_UNSUPPORTED_EMBED: $it")
                }
            }.distinct().sorted()
        }
        val archiveConfiguration = project.configurations.resolvable("fatAar${suffix}Archives") {
            isTransitive = false // The graph validator requires every embedded node explicitly.
            shouldResolveConsistentlyWith(variant.runtimeConfiguration)
            variant.runtimeConfiguration.attributes.keySet().forEach { key ->
                @Suppress("UNCHECKED_CAST")
                attributes.attribute(key as Attribute<Any>, variant.runtimeConfiguration.attributes.getAttribute(key)!!)
            }
            dependencies.addAllLater(project.provider {
                embedScopes.flatMap { it.dependencies }.map { dependency -> dependency.copy().also {
                    if (it is ProjectDependency) it.capabilities { requireCapability(inputCapability(it.path)) }
                } }
            })
        }
        val archives = archiveConfiguration.get().incoming.artifacts
        val publicApi = project.configurations.getByName("fatAar${suffix}PublicApi").incoming.artifacts
        val nativeRuntime = variant.runtimeConfiguration.incoming.artifactView {
            // Select whole Maven archives/JVM project JARs explicitly. Asking AGP
            // for untyped project artifacts is ambiguous across its secondary views
            // and can hide the validator's missing-dependency diagnostic.
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-aar-or-jar")
            componentFilter { archiveIdentity(it) !in embeddedKeys.get() }
        }.artifacts
        val merge = project.tasks.register<MergeAarTask>("merge${suffix}FatAar") {
            group = "fat aar"
            dependsOn(check, archives.artifactFiles, publicApi.artifactFiles, nativeRuntime.artifactFiles)
            resourceWinners.set(fatAarExtension.resourceWinners)
            prefabPackageName.set(fatAarExtension.prefabPackageName)
            nativeAbis.set(fatAarExtension.nativeAbis)
            externalRuntime.set(nativeRuntime.resolvedArtifacts.map { artifacts ->
                artifacts.map { MergeInput(archiveIdentity(it.id.componentIdentifier), it.file) }.sortedWith(compareBy({ it.owner }, { it.file.name }))
            })
            externalApi.set(publicApi.resolvedArtifacts.map { artifacts ->
                artifacts.map { MergeInput(archiveIdentity(it.id.componentIdentifier), it.file) }
                    .sortedWith(compareBy({ it.owner }, { it.file.name }))
            })
            expectedOwners.set(embeddedKeys)
            embedded.set(archives.resolvedArtifacts.map { artifacts ->
                artifacts.map { MergeInput(archiveIdentity(it.id.componentIdentifier), it.file) }.sortedBy { it.owner }
            })
            reportFile.set(project.layout.buildDirectory.file("reports/fat-aar/${variant.name}/merge.txt"))
        }
        variant.artifacts.use(merge).wiredWithFiles(MergeAarTask::inputAar, MergeAarTask::outputAar)
            .toTransform(SingleArtifact.AAR)
        val linkage = project.tasks.register<ValidateLinkageTask>("validate${suffix}Linkage") {
            group = "verification"
            dependsOn(nativeRuntime.artifactFiles)
            externalRuntime.set(merge.flatMap { it.externalRuntime })
            platformClasspath.from(platform)
            reportFile.set(project.layout.buildDirectory.file("reports/fat-aar/${variant.name}/linkage.txt"))
        }
        variant.artifacts.use(linkage).wiredWithFiles(ValidateLinkageTask::inputAar, ValidateLinkageTask::outputAar)
            .toTransform(SingleArtifact.AAR)
        val classes = project.tasks.register<ExtractMergedClassesTask>("extract${suffix}FatAarClasses") {
            aar.set(variant.artifacts.get(SingleArtifact.AAR))
            classesJar.set(project.layout.buildDirectory.file("intermediates/fat-aar/${variant.name}/classes.jar"))
        }

        val compileClasses = project.tasks.register<ExtractMergedClassesTask>("extract${suffix}FatAarCompileClasses") {
            aar.set(variant.artifacts.get(SingleArtifact.AAR))
            includeResourceStubs.set(true)
            classesJar.set(project.layout.buildDirectory.file("intermediates/fat-aar/${variant.name}/compile-classes.jar"))
        }
        val prefab = project.tasks.register<ExtractMergedPrefabTask>("extract${suffix}FatAarPrefab") {
            aar.set(variant.artifacts.get(SingleArtifact.AAR))
            configurationFingerprint.set(project.layout.buildDirectory.file("intermediates/fat-aar/${variant.name}/prefab-configuration.json"))
        }

        // A dedicated capability makes project consumers select this AAR, not AGP's thin class JARs.
        // The default capability is also present so normal Maven consumers need only SDK coordinates.
        val component = components.adhoc("fat$suffix")
        project.components.add(component)
        fun outgoing(api: Boolean, published: Boolean): Configuration {
            val source = if (api) variant.compileConfiguration else variant.runtimeConfiguration
            val scopes = variant.runtimeConfiguration.hierarchy.filter {
                it.name == "api" || it.name.endsWith("Api") || (!api &&
                    (it.name == "implementation" || it.name.endsWith("Implementation") ||
                        it.name == "runtimeOnly" || it.name.endsWith("RuntimeOnly")))
            }
            val name = "fat${suffix}${if (published) "Published" else ""}${if (api) "Api" else "Runtime"}Elements"
            val configuration = if (published) project.configurations.create(name) {
                isCanBeConsumed = false // Publication model only; project consumers select typed variants above.
                isCanBeResolved = false
            } else project.configurations.consumable(name).get()
            return configuration.apply {
                // Each Maven component publishes one chosen variant, consumable by debug and release apps.
                source.attributes.keySet().filter { !published || !it.name.startsWith("com.android.") }.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    attributes.attribute(key as Attribute<Any>, source.attributes.getAttribute(key)!!)
                }
                attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, if (api) Usage.JAVA_API else Usage.JAVA_RUNTIME))
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, "aar"))
                // Copy direct scope declarations, not implementation's inherited embed bucket.
                dependencies.addAllLater(project.provider { scopes.flatMap { it.dependencies }.map { it.copy() } })
                dependencyConstraints.addAllLater(project.provider { scopes.flatMap { it.dependencyConstraints } })
                outgoing.artifact(variant.artifacts.get(SingleArtifact.AAR)) { type = "aar"; extension = "aar" }
                // AGP dexing needs a classes artifact for project dependencies. A generic jar
                // also matches lint's AAR_OR_JAR query and breaks its wrapped-AAR model;
                // use AGP's classes type so lint continues to select the complete AAR.
                if (!published) outgoing.variants.create("mergedClasses") {
                    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
                    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
                    artifact((if (api) compileClasses else classes).flatMap { it.classesJar }) { type = "jar"; extension = "jar" }
                }
                if (!published) {
                    // AGP 9.4 configureCMake depends on android-prefab-configuration, not
                    // android-prefab. It uses these files as configure fingerprints only.
                    // The genuine merged prefab.json carries the producer task dependency;
                    // returning an empty transformed collection loses that dependency.
                    outgoing.variants.create("mergedPrefabConfiguration") {
                        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-prefab-configuration")
                        artifact(prefab.flatMap { it.configurationFingerprint }) { type = "android-prefab-configuration" }
                    }
                }
                if (!published) outgoing.capability("${project.group}:${project.name}:${project.version}")
                if (!published) outgoing.capability("${project.group}:${project.name}-fat:${project.version}")
            }
        }
        outgoing(true, false)
        outgoing(false, false)
        component.addVariantsFromConfiguration(outgoing(true, true)) { mapToMavenScope("compile") }
        component.addVariantsFromConfiguration(outgoing(false, true)) { mapToMavenScope("runtime") }
        if (fatAarExtension.sourcesJarEnabled.get())
            attachMergedSources(project, variant, archiveConfiguration.get(), component, check)
    }
}
