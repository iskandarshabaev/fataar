package com.fataar.gradle

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Category

/** Plain strings are the task inputs. No Project, Configuration or file paths cross into task actions. */
internal object DependencyGraph {
    private fun failureDetails(failure: Throwable): String {
        val seen = mutableSetOf<Throwable>()
        return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }
            .mapNotNull { it.message }.distinct().joinToString(" Cause: ")
            .replace(Regex("[\\r\\n\\t]+"), " ")
    }
    fun snapshot(root: ResolvedComponentResult, variant: ResolvedVariantResult): List<String> {
        val records = sortedSetOf<String>()
        val visited = mutableSetOf<Pair<String, ResolvedVariantResult>>()
        fun id(component: ResolvedComponentResult): String = when (val value = component.id) {
            is ProjectComponentIdentifier -> if (value.build.buildPath == ":") "project:" + value.projectPath
                else "included:" + value.build.buildPath + value.projectPath
            is ModuleComponentIdentifier -> "module:" + value.group + ":" + value.module
            else -> "unsupported:" + value.displayName
        }
        fun visit(component: ResolvedComponentResult, selected: ResolvedVariantResult) {
            val key = id(component)
            if (!visited.add(key to selected)) return
            records += listOf("node", key, component.moduleVersion?.version.orEmpty()).joinToString("\t")
            selected.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name?.let { category ->
                if (category in setOf(Category.REGULAR_PLATFORM, Category.ENFORCED_PLATFORM))
                    records += listOf("platform", key, category).joinToString("\t")
            }
            component.getDependenciesForVariant(selected).forEach { dependency ->
                when (dependency) {
                    is ResolvedDependencyResult -> {
                        // A constraint influences selection; it is not itself a payload dependency.
                        if (!dependency.isConstraint) {
                            val requested = when (val selector = dependency.requested) {
                                is ProjectComponentSelector -> "project:" + selector.projectPath
                                is ModuleComponentSelector -> "module:" + selector.group + ":" + selector.module
                                else -> "unsupported:" + selector.displayName
                            }
                            records += listOf("edge", key, id(dependency.selected), requested).joinToString("\t")
                            visit(dependency.selected, dependency.resolvedVariant)
                        }
                    }
                    is UnresolvedDependencyResult ->
                        records += listOf("unresolved", key, dependency.attempted.displayName,
                            failureDetails(dependency.failure)).joinToString("\t")
                }
            }
        }
        records += "root\t" + id(root)
        visit(root, variant)
        return records.toList()
    }
}

internal data class DependencyIssue(val code: String, val message: String, val fix: String? = null)
internal data class DependencyAnalysis(val report: String, val issues: List<DependencyIssue>)

/** Classifies the resolved runtime graph against declarations on the SDK itself. */
internal object DependencyValidator {
    fun analyze(graph: List<String>, declarations: List<String>, prefixes: List<String>, fixScope: String = "embed"): DependencyAnalysis {
        val rows = graph.map { it.split('\t') }
        val root = rows.single { it[0] == "root" }[1]
        val nodes = rows.filter { it[0] == "node" }.associate { it[1] to it.getOrElse(2) { "" } }
        val platforms = rows.filter { it[0] == "platform" }.groupBy({ it[1] }, { it[2] })
        val edges = rows.filter { it[0] == "edge" }.groupBy { it[1] }
        val direct = edges[root].orEmpty()
        val issues = mutableListOf<DependencyIssue>()
        platforms.filterValues { Category.ENFORCED_PLATFORM in it }.keys.sorted().forEach { node ->
            issues += DependencyIssue("FATAAR_ENFORCED_PLATFORM", "$node: enforcedPlatform propagates forced versions; use platform(...) or explicit compatible constraints before publishing the SDK.")
        }
        val roles = mutableMapOf<String, MutableSet<String>>()
        declarations.forEach { record ->
            val (scope, requested) = record.split('\t', limit = 2)
            val matches = direct.filter { it[3] == requested }
            matches.forEach { roles.getOrPut(it[2]) { sortedSetOf() }.add(scope) }
        }
        roles.toSortedMap().forEach { (node, scopes) ->
            if (node in platforms && "embed" in scopes)
                issues += DependencyIssue("FATAAR_EMBED_PLATFORM", "$node has no AAR/JAR payload. Declare api(platform(...)) or implementation(platform(...)); declare each actual library separately.")
            if ("embed" in scopes && scopes.any { it != "embed" }) {
                issues += DependencyIssue("FATAAR_CONFLICTING_DELIVERY", "$node is both embed and external: $scopes")
            }
        }
        val embedded = roles.filterValues { "embed" in it }.keys
        val external = roles.filterValues { scopes -> scopes.any { it in setOf("api", "implementation", "runtimeOnly") } }.keys
        val coveredExternal = mutableSetOf<String>()
        fun cover(node: String) {
            if (node in embedded || !coveredExternal.add(node)) return
            edges[node].orEmpty().forEach { cover(it[2]) }
        }
        external.forEach(::cover)
        val paths = linkedMapOf(root to listOf(root))
        val queue = ArrayDeque<String>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            edges[node].orEmpty().sortedBy { it[2] }.forEach { edge ->
                if (edge[2] !in paths) {
                    paths[edge[2]] = paths.getValue(node) + edge[2]
                    queue.add(edge[2])
                }
            }
        }
        nodes.keys.sorted().filter { it != root }.forEach { node ->
            val path = paths[node].orEmpty().joinToString(" -> ")
            if (node.startsWith("included:") || node.startsWith("unsupported:")) {
                issues += DependencyIssue("FATAAR_UNSUPPORTED_COMPONENT", "$node via $path")
            } else if (node in platforms && node !in coveredExternal && node !in embedded) {
                issues += DependencyIssue("FATAAR_MISSING_PLATFORM",
                    "$node:${nodes[node]} via $path. Account for the BOM explicitly with api(platform(...)) or implementation(platform(...)); a BOM does not deliver its libraries. No automatic change.")
            } else if (node !in embedded && node !in coveredExternal) {
                val projectPath = node.removePrefix("project:")
                val fix = if (node.startsWith("project:") && prefixes.any { projectPath.startsWith(it) })
                    "$fixScope project $projectPath" else null
                issues += DependencyIssue("FATAAR_MISSING_DEPENDENCY",
                    "$node:${nodes[node]} via $path. Declare embed(...) or external api/implementation/runtimeOnly." +
                        if (fix != null) " Auto-fix: $fix" else " Delivery is ambiguous; no automatic change.", fix)
            }
        }
        rows.filter { it[0] == "unresolved" }.forEach {
            issues += DependencyIssue("FATAAR_UNRESOLVED_DEPENDENCY",
                paths[it[1]].orEmpty().joinToString(" -> ") + " -> " + it[2] + ": " + it[3])
        }
        val report = buildString {
            appendLine("format=1")
            appendLine("coverage=runtime-dependency-accounting")
            appendLine("packaging=modular-baseline")
            nodes.keys.sorted().filter { it != root }.forEach { node ->
                val role = when {
                    node in platforms -> "platform"
                    node in embedded -> "embed"
                    node in coveredExternal -> "external"
                    else -> "missing"
                }
                appendLine("$role $node:${nodes[node]}")
            }
            issues.forEach { appendLine(it.code + ": " + it.message) }
            appendLine("result=" + if (issues.isEmpty()) "PASS" else "FAIL")
        }
        return DependencyAnalysis(report, issues)
    }
}

internal object DependencyRules {
    fun parse(text: String): List<Triple<String, String, String>> =
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { line ->
            val fields = line.split(Regex("\\s+"))
            require(fields.size == 3 && (isEmbedScope(fields[0]) || fields[0] in setOf("api", "implementation", "runtimeOnly")) &&
                fields[1] in setOf("project", "module")) { "FATAAR_INVALID_RULE: $line" }
            require(if (fields[1] == "project") fields[2].startsWith(":")
                else fields[2].split(':').size == 3 && fields[2].split(':').all { it.isNotBlank() }) {
                "FATAAR_INVALID_RULE: $line"
            }
            Triple(fields[0], fields[1], fields[2])
        }.toList()
}
