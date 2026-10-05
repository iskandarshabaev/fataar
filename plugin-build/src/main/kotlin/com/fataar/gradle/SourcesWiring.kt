package com.fataar.gradle

import com.android.build.api.variant.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.*
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun registerAndroidSources(project: Project, variant: LibraryVariant, taskName: String): TaskProvider<ExportSourcesTask> {
    val bindingEnabled = project.extensions.getByType(com.android.build.api.dsl.LibraryExtension::class.java)
        .buildFeatures.dataBinding == true
    val processorSources = project.files()
    val task = project.tasks.register<ExportSourcesTask>(taskName) {
        group = "fat aar"
        owner.set("project:" + project.path)
        variantName.set(variant.name)
        moduleVersion.set(project.version.toString())
        dataBinding.set(bindingEnabled)
        sourceRoots.from(processorSources)
        // The public source API covers pre-compilation generators. javac processors
        // produce another source directory during compilation, so build the AAR too.
        dependsOn(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.AAR))
        variant.sources.java?.let { sourceRoots.from(it.all) }
        variant.sources.kotlin?.let { sourceRoots.from(it.all) }
        variant.sources.aidl?.let { sourceRoots.from(it.all) }
        variant.sources.java?.let { staticSourceRoots.from(it.static) }
        variant.sources.kotlin?.let { staticSourceRoots.from(it.static) }
        variant.sources.aidl?.let { staticSourceRoots.from(it.static) }
        sourcesJar.set(project.layout.buildDirectory.file("intermediates/fat-aar/sources/${variant.name}/$taskName.jar"))
    }
    variant.configureJavaCompileTask { javac ->
        processorSources.from(javac.options.generatedSourceOutputDirectory)
    }
    return task
}

internal fun sourcesAttributes(project: Project, configuration: Configuration, variant: LibraryVariant, published: Boolean) {
    variant.runtimeConfiguration.attributes.keySet().filter { !published || !it.name.startsWith("com.android.") }.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        configuration.attributes.attribute(key as Attribute<Any>, variant.runtimeConfiguration.attributes.getAttribute(key)!!)
    }
    configuration.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    configuration.attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.DOCUMENTATION))
    configuration.attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, project.objects.named(DocsType::class.java, DocsType.SOURCES))
    configuration.attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling::class.java, Bundling.EXTERNAL))
    configuration.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
}

internal fun attachMergedSources(project: Project, variant: LibraryVariant, archives: Configuration,
                                 component: AdhocComponentWithVariants, check: TaskProvider<ValidateDependenciesTask>) {
    val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
    val own = registerAndroidSources(project, variant, "package${suffix}FatAarOwnSources")
    val sources = archives.incoming.artifactView {
        withVariantReselection()
        isLenient = true // Missing variants are reported below with the exact embedded owners.
        variant.runtimeConfiguration.attributes.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            attributes.attribute(key as Attribute<Any>, variant.runtimeConfiguration.attributes.getAttribute(key)!!)
        }
        attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.DOCUMENTATION))
        attributes.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, project.objects.named(DocsType::class.java, DocsType.SOURCES))
        attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling::class.java, Bundling.EXTERNAL))
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
    }.artifacts
    val host = "project:" + project.path
    val merge = project.tasks.register<MergeSourcesTask>("merge${suffix}FatAarSources") {
        group = "fat aar"
        dependsOn(check, own, sources.artifactFiles)
        sourceArchives.set(sources.resolvedArtifacts.zip(own.flatMap { it.sourcesJar }) { artifacts, root ->
            artifacts.map { MergeInput(archiveIdentity(it.id.componentIdentifier), it.file) } + MergeInput(host, root.asFile)
        })
        expectedOwners.set(archives.incoming.resolutionResult.rootComponent.map { root ->
            listOf(host) + root.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .filter { !it.isConstraint }.map { archiveIdentity(it.selected.id) }.distinct().sorted()
        })
        sourcesJar.set(project.layout.buildDirectory.file("outputs/sources/${project.name}-${variant.name}-sources.jar"))
        reportFile.set(project.layout.buildDirectory.file("reports/fat-aar/${variant.name}/sources.txt"))
    }
    fun outgoing(published: Boolean): Configuration {
        val name = "fat${suffix}${if (published) "Published" else ""}SourcesElements"
        val configuration = if (published) project.configurations.create(name) {
            isCanBeConsumed = false; isCanBeResolved = false
        } else project.configurations.consumable(name).get()
        sourcesAttributes(project, configuration, variant, published)
        configuration.outgoing.artifact(merge.flatMap { it.sourcesJar }) { type = "jar"; extension = "jar"; classifier = "sources" }
        if (!published) {
            configuration.outgoing.capability("${project.group}:${project.name}:${project.version}")
            configuration.outgoing.capability("${project.group}:${project.name}-fat:${project.version}")
        }
        return configuration
    }
    outgoing(false)
    component.addVariantsFromConfiguration(outgoing(true)) { mapToOptional() }
}
