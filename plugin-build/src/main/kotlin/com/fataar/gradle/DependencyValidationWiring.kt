package com.fataar.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal class DependencyValidationWiring(private val project: Project, private val extension: FatAarExtension) {
    private val rulesFile = project.layout.projectDirectory.file("fat-aar-dependencies.txt")
    private val rules = DependencyRules.parse(project.providers.fileContents(rulesFile).asText.orElse("").get())

    fun validateJvmScopes() {
        rules.firstOrNull { isEmbedScope(it.first) && it.first != "embed" }?.let {
            throw GradleException("FATAAR_INVALID_RULE_SCOPE: ${it.first} requires an Android source set or variant; JVM libraries use embed")
        }
    }

    fun prepare() {
        project.configurations.named("implementation") {
            extendsFrom(project.configurations.getByName("embed"))
        }
        applyRules("embed")
        rules.filter { !isEmbedScope(it.first) }.forEach { (scope, kind, notation) ->
            project.dependencies.add(scope, if (kind == "project")
                project.dependencies.project(mapOf("path" to notation)) else notation)
        }
    }

    private val initializedScopes = mutableSetOf<String>()
    private fun applyRules(scope: String) {
        if (!initializedScopes.add(scope)) return
        rules.filter { it.first == scope }.forEach { (_, kind, notation) ->
            project.dependencies.add(scope, if (kind == "project")
                project.dependencies.project(mapOf("path" to notation)) else notation)
        }
    }

    fun prepareAndroidScopes() {
        project.extensions.getByType(LibraryExtension::class.java).sourceSets.configureEach {
            if (name != "main") {
                val sourceName = name
                val embed = ensureScope(sourceName)
                project.configurations.configureEach {
                    if (name == sourceName + "Implementation") extendsFrom(embed)
                }
            }
        }
        project.afterEvaluate {
            rules.filter { isEmbedScope(it.first) }.forEach { (scope, _, _) ->
                if (project.configurations.findByName(scope) == null)
                    throw GradleException("FATAAR_INVALID_RULE_SCOPE: $scope has no Android source set or variant")
            }
        }
    }

    private fun ensureScope(name: String): Configuration {
        val scope = name + "Embed"
        val configuration = project.configurations.findByName(scope)
            ?: project.configurations.dependencyScope(scope).get()
        applyRules(scope)
        return configuration
    }

    fun prepareVariantScope(name: String, compile: Configuration, runtime: Configuration) {
        val embed = ensureScope(name)
        compile.extendsFrom(embed)
        runtime.extendsFrom(embed)
    }

    fun register(suffix: String, runtime: Configuration, fixScope: String = "embed"): TaskProvider<ValidateDependenciesTask> {
        val resolution = runtime.incoming.resolutionResult
        val graph = resolution.rootComponent.zip(resolution.rootVariant, DependencyGraph::snapshot)
        // Configured lazily, after the build script has declared its dependencies.
        fun declarations(): List<String> {
            val entries = sortedSetOf<String>()
            val ancestors = mutableSetOf<Configuration>()
            fun visit(configuration: Configuration) {
                if (!ancestors.add(configuration)) return
                configuration.extendsFrom.forEach(::visit)
            }
            visit(runtime)
            ancestors.forEach { configuration ->
                val name = configuration.name
                val scope = when {
                    isEmbedScope(name) -> "embed"
                    name == "api" || name.endsWith("Api") -> "api"
                    name == "implementation" || name.endsWith("Implementation") -> "implementation"
                    name == "runtimeOnly" || name.endsWith("RuntimeOnly") -> "runtimeOnly"
                    else -> null
                }
                configuration.dependencies.forEach { dependency ->
                    val category = (dependency as? ModuleDependency)?.attributes?.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
                    if (scope == "embed" && category in setOf(Category.REGULAR_PLATFORM, Category.ENFORCED_PLATFORM))
                        throw GradleException("FATAAR_EMBED_PLATFORM: $name contains ${dependency.group}:${dependency.name}; BOMs have no AAR/JAR. Use api(platform(...)) or implementation(platform(...)), and embed actual libraries separately.")
                    val key = when (dependency) {
                        is ProjectDependency -> "project:" + dependency.path
                        is ExternalModuleDependency -> "module:" + dependency.group + ":" + dependency.name
                        is FileCollectionDependency -> throw GradleException(
                            "FATAAR_UNSUPPORTED_FILE_DEPENDENCY: $name contains a file dependency. Use a module/project coordinate.")
                        else -> throw GradleException("FATAAR_UNSUPPORTED_DECLARATION: $name: $dependency")
                    }
                    if (scope != null) entries += "$scope\t$key"
                    if (scope != null && category == Category.ENFORCED_PLATFORM)
                        throw GradleException("FATAAR_ENFORCED_PLATFORM: $name contains $key; publishing enforcedPlatform propagates forced versions to SDK consumers. Use platform(...) or explicit constraints with a documented compatibility range.")
                }
            }
            return entries.toList()
        }
        val check = project.tasks.register<ValidateDependenciesTask>("validate" + suffix + "Dependencies") {
            group = "verification"
            description = "Validates explicit dependency accounting; does not merge AARs."
            this.graph.set(graph)
            this.declarations.set(declarations())
            this.fixScope.set(fixScope)
            autoEmbedProjectPrefixes.set(extension.autoEmbedProjectPrefixes)
            reportFile.set(project.layout.buildDirectory.file("reports/fat-aar/" +
                suffix.ifEmpty { "main" }.replaceFirstChar { it.lowercaseChar() } + "/dependencies.txt"))
        }
        project.tasks.register<AddMissingDependenciesTask>("addMissing" + suffix + "Dependencies") {
            group = "fat aar"
            description = "Adds unambiguous missing declarations to fat-aar-dependencies.txt for the next build."
            this.graph.set(graph)
            this.declarations.set(declarations())
            this.fixScope.set(fixScope)
            autoEmbedProjectPrefixes.set(extension.autoEmbedProjectPrefixes)
            this.rulesFile.set(this@DependencyValidationWiring.rulesFile)
        }
        return check
    }

    fun registerAbi(suffix: String, runtime: Configuration, compile: Configuration,
                    platform: Provider<List<RegularFile>>, check: TaskProvider<ValidateDependenciesTask>): TaskProvider<ValidateAbiTask> {
        val api = project.configurations.resolvable("fatAar" + suffix + "PublicApi") {
            extendsFrom(*runtime.hierarchy.filter { it.name == "api" || it.name.endsWith("Api") }.toTypedArray())
            compile.attributes.keySet().forEach { key ->
                @Suppress("UNCHECKED_CAST")
                attributes.attribute(key as Attribute<Any>, compile.attributes.getAttribute(key)!!)
            }
            shouldResolveConsistentlyWith(compile)
        }
        // AGP exposes several secondary artifacts; select its class-JAR view explicitly.
        // Keep this AGP artifact-type convention isolated and covered by the Android integration tests.
        fun artifacts(configuration: Configuration) = configuration.incoming.artifactView {
            attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
        }.artifacts
        fun inputs(collection: ArtifactCollection) = collection.resolvedArtifacts.map { values ->
            values.map { artifact ->
                val owner = when (val id = artifact.id.componentIdentifier) {
                    is ProjectComponentIdentifier -> if (id.build.buildPath == ":") "project:" + id.projectPath
                        else "included:" + id.build.buildPath + id.projectPath
                    is ModuleComponentIdentifier -> "module:" + id.group + ":" + id.module
                    else -> "unsupported:" + artifact.file.name
                }
                DependencyArtifact(owner, artifact.file)
            }.sortedWith(compareBy({ it.owner }, { it.file.name }))
        }
        return project.tasks.register<ValidateAbiTask>("validate" + suffix + "Abi") {
            group = "verification"
            description = "Validates JVM API type availability and duplicate classes before delivering the AAR."
            graph.set(check.flatMap { it.graph })
            declarations.set(check.flatMap { it.declarations })
            val runtimeCollection = artifacts(runtime)
            val apiCollection = artifacts(api.get())
            // Mapping metadata to plain nested values loses producer task dependencies.
            // Preserve them through the original FileCollections, without naming AGP tasks.
            dependsOn(runtimeCollection.artifactFiles, apiCollection.artifactFiles)
            runtimeArtifacts.set(inputs(runtimeCollection))
            apiArtifacts.set(inputs(apiCollection))
            platformClasspath.from(platform)
            reportFile.set(project.layout.buildDirectory.file("reports/fat-aar/" +
                suffix.replaceFirstChar { it.lowercaseChar() } + "/abi.txt"))
        }
    }
}
