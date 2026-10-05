package com.fataar.gradle

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.GradleException

/** One AAR exposes one Prefab package; preserve module names and binary/header bytes. */
internal object PrefabMerger {
    data class Source(val owner: String, val entries: Map<String, ByteArray>)
    private data class Package(val source: Source, val metadata: JsonObject, val name: String, val modules: Set<String>)
    fun fail(owner: String, reason: String): Nothing = throw GradleException("FATAAR_PREFAB_METADATA: $owner: $reason")
    fun json(bytes: ByteArray, owner: String): JsonObject = try {
        JsonParser.parseString(bytes.decodeToString()).asJsonObject
    } catch (error: RuntimeException) { fail(owner, "invalid JSON: ${error.message}") }
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
    private fun packageOf(source: Source): Package? {
        val paths = source.entries.keys.filter { it.startsWith("prefab/") }
        if (paths.isEmpty()) return null
        val root = source.entries["prefab/prefab.json"] ?: fail(source.owner, "missing prefab/prefab.json")
        val metadata = json(root, source.owner)
        try {
            if (metadata.get("schema_version")?.asInt != 2) fail(source.owner, "expected Prefab schema 2")
            val name = metadata.get("name").asString
            if (!identifier.matches(name)) fail(source.owner, "invalid package name $name")
            val modules = paths.filter { it.startsWith("prefab/modules/") }.map { it.removePrefix("prefab/modules/").substringBefore('/') }.toSet()
            if (modules.isEmpty() || modules.any { !identifier.matches(it) }) fail(source.owner, "invalid or absent modules")
            paths.forEach { path ->
                if (path != "prefab/prefab.json" && !path.startsWith("prefab/modules/")) fail(source.owner, "unsupported path $path")
                if (path.startsWith("prefab/modules/")) {
                    val relative = path.removePrefix("prefab/modules/").substringAfter('/', "")
                    if (relative != "module.json" && !relative.startsWith("include/") && !relative.startsWith("libs/android."))
                        fail(source.owner, "unsupported path $path")
                }
            }
            return Package(source, metadata, name, modules)
        } catch (error: RuntimeException) {
            if (error is GradleException) throw error
            fail(source.owner, "invalid package metadata: ${error.message}")
        }
    }

    fun merge(sources: List<Source>, target: String, externalSources: List<Source> = emptyList()): Map<String, ByteArray> {
        val packages = sources.mapNotNull(::packageOf)
        if (packages.isEmpty()) return emptyMap()
        if (!identifier.matches(target)) fail("SDK", "invalid target package $target")
        val byName = linkedMapOf<String, Package>()
        val moduleOwners = linkedMapOf<String, Package>()
        packages.forEach { pkg ->
            byName.put(pkg.name, pkg)?.let { fail(pkg.source.owner, "duplicate package ${pkg.name}: ${it.source.owner}") }
            pkg.modules.forEach { module ->
                moduleOwners.put(module, pkg)?.let {
                    throw GradleException("FATAAR_PREFAB_MODULE_CONFLICT: $module: ${it.source.owner} and ${pkg.source.owner}")
                }
            }
        }
        val external = linkedMapOf<String, Package>()
        externalSources.mapNotNull(::packageOf).forEach { pkg ->
            external.put(pkg.name, pkg)?.let { fail(pkg.source.owner, "duplicate external package ${pkg.name}: ${it.source.owner}") }
        }
        val dependencies = sortedSetOf<String>()
        val result = sortedMapOf<String, ByteArray>()
        fun requireExternal(name: String, module: String?, owner: String) {
            val pkg = external[name] ?: fail(owner, "missing external package $name; declare its AAR in SDK api or embed")
            if (module != null && module !in pkg.modules) fail(owner, "external package $name has no module $module")
            dependencies += name
        }
        packages.forEach { pkg ->
            try {
                pkg.metadata.getAsJsonArray("dependencies")?.forEach { element ->
                    val name = element.asString
                    if (name !in byName) requireExternal(name, null, pkg.source.owner)
                }
                pkg.modules.sorted().forEach { module ->
                    val path = "prefab/modules/$module/module.json"
                    val metadata = pkg.source.entries[path]?.let { json(it, pkg.source.owner) } ?: JsonObject()
                    fun exports(objectValue: JsonObject) {
                        val libraries = objectValue.getAsJsonArray("export_libraries") ?: return
                        val rewritten = JsonArray()
                        libraries.forEach { element ->
                            val ref = element.asString
                            val destination = when {
                                ref.startsWith(":") -> {
                                    if (ref.removePrefix(":") !in pkg.modules) fail(pkg.source.owner, "absent module $ref in ${pkg.name}")
                                    ref
                                }
                                ref.startsWith("//") -> {
                                    val match = Regex("//([^:]+):([^:]+)").matchEntire(ref) ?: fail(pkg.source.owner, "invalid module reference $ref")
                                    val (name, member) = match.destructured
                                    if (name in byName) {
                                        if (member !in byName.getValue(name).modules) fail(pkg.source.owner, "absent module $ref")
                                        ":$member"
                                    } else { requireExternal(name, member, pkg.source.owner); ref }
                                }
                                else -> ref // Literal linker flags retain their standard Prefab meaning.
                            }
                            rewritten.add(destination)
                        }
                        objectValue.add("export_libraries", rewritten)
                    }
                    exports(metadata)
                    metadata.getAsJsonObject("android")?.let(::exports)
                    val prefix = "prefab/modules/$module/"
                    pkg.source.entries.filterKeys { it.startsWith(prefix) && it != path }.forEach { (name, bytes) -> result[name] = bytes }
                    result[path] = metadata.toString().toByteArray()
                }
            } catch (error: RuntimeException) {
                if (error is GradleException) throw error
                fail(pkg.source.owner, "invalid module metadata: ${error.message}")
            }
        }
        val metadata = JsonObject().apply {
            addProperty("schema_version", 2); addProperty("name", target)
            add("dependencies", JsonArray().apply { dependencies.forEach(::add) })
            // The host is first. Child package versions cannot describe the fused SDK.
            packages.firstOrNull { it.source.owner == "sdk" }?.metadata?.get("version")?.let { add("version", it) }
        }
        result["prefab/prefab.json"] = metadata.toString().toByteArray()
        return result
    }
}
