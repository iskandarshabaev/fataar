package com.fataar.gradle

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.GradleException
import java.nio.charset.Charset

/** Keep class/BR namespaces; only the resource owner changes when the AAR is fused. */
internal data class BindingResourceOwner(val owner: String, val classes: Set<String>, val symbols: Set<String>)

internal object DataBindingMetadata {
    private fun fail(path: String, owner: String, reason: String): Nothing =
        throw GradleException("FATAAR_DATABINDING_METADATA: $owner: $path: $reason")

    private fun charset(bytes: ByteArray): Charset = when {
        bytes.size >= 2 && ((bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) ||
            (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte())) -> Charsets.UTF_16
        else -> Charsets.UTF_8
    }

    private fun json(path: String, bytes: ByteArray, owner: String): JsonObject = try {
        JsonParser.parseString(bytes.toString(charset(bytes))).asJsonObject
    } catch (error: RuntimeException) { fail(path, owner, "invalid JSON: ${error.message}") }

    fun rewrite(path: String, bytes: ByteArray, owner: String, target: String, namespaces: Set<String>, external: Map<String, BindingResourceOwner> = emptyMap()): ByteArray {
        if (path.endsWith("-br.bin")) {
            if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0xac.toByte(), 0xed.toByte(), 0, 5)))
                fail(path, owner, "unsupported BR serialization header")
            return bytes // The consuming AGP reads BR declarations and generates the BR classes.
        }
        val root = json(path, bytes, owner)
        if (path.endsWith("-setter_store.json")) {
            if (root.get("version")?.toString() != "5" || root.get("useAndroidX")?.toString() != "true")
                fail(path, owner, "expected AndroidX setter-store version 5")
            return bytes
        }
        var changed = false
        try {
            root.getAsJsonObject("mappings").entrySet().forEach { (_, item) ->
                val mapping = item.asJsonObject
                val module = mapping.get("module_package").asString
                // AGP infers the binding-class package for imported plain ViewBinding
                // layouts (no implementations), rather than recording the R namespace.
                val importedViewBinding = module.endsWith(".databinding") &&
                    module.removeSuffix(".databinding") in (namespaces + external.keys) &&
                    mapping.getAsJsonArray("implementations").isEmpty &&
                    mapping.get("qualified_name").asString.startsWith("$module.")
                val resourceOwner = if (importedViewBinding) module.removeSuffix(".databinding") else module
                val destination = when (resourceOwner) {
                    in namespaces -> target
                    in external -> resourceOwner
                    else -> fail(path, owner, "unknown resource namespace $module; declare its Android library in SDK api or embed")
                }
                if (module != destination) { mapping.addProperty("module_package", destination); changed = true }
            }
        } catch (error: RuntimeException) {
            if (error is GradleException) throw error
            fail(path, owner, "invalid binding class log: ${error.message}")
        }
        return if (changed) root.toString().toByteArray(charset(bytes)) else bytes
    }

    fun validate(entries: Map<String, ByteArray>, classes: Set<String>, symbols: Set<String>, owners: Map<String, String>, external: Map<String, BindingResourceOwner> = emptyMap()) {
        fun owner(path: String) = owners[path].orEmpty()
        val packages = entries.keys.filter { it.startsWith("data-binding/") || it.startsWith("data-binding-base-class-log/") }
            .map { it.substringAfter('/').substringBefore('-') }.toSet()
        packages.forEach { namespace ->
            val paths = listOf("data-binding/$namespace-br.bin", "data-binding/$namespace-setter_store.json",
                "data-binding-base-class-log/$namespace-binding_classes.json")
            paths.forEach { if (it !in entries) fail(it, paths.firstNotNullOfOrNull { p -> owners[p] }.orEmpty(), "missing paired metadata") }
            val mapper = namespace.replace('.', '/') + "/DataBinderMapperImpl.class"
            if (mapper !in classes) fail(paths.first(), owner(paths.first()), "missing mapper class $mapper")
        }
        entries.filterKeys { it.startsWith("data-binding-base-class-log/") }.forEach { (path, bytes) ->
            try {
                json(path, bytes, owner(path)).getAsJsonObject("mappings").entrySet().forEach { (layout, item) ->
                    val mapping = item.asJsonObject
                    val resourceOwner = mapping.get("module_package").asString
                    val dependency = external[resourceOwner]
                    val availableSymbols = dependency?.symbols ?: symbols
                    val availableClasses = dependency?.classes ?: classes
                    if ("layout/$layout" !in availableSymbols) fail(path, owner(path),
                        "missing resource layout/$layout in $resourceOwner (${dependency?.owner ?: "SDK"})")
                    val names = listOf(mapping.get("qualified_name").asString) +
                        mapping.getAsJsonArray("implementations").map { it.asJsonObject.get("qualified_name").asString }
                    names.forEach { name ->
                        if (name.replace('.', '/') + ".class" !in availableClasses)
                            throw GradleException("FATAAR_DATABINDING_CLASS: ${owner(path)}: $path references absent $name in $resourceOwner (${dependency?.owner ?: "SDK"})")
                    }
                }
            } catch (error: RuntimeException) {
                if (error is GradleException) throw error
                fail(path, owner(path), "invalid binding class log: ${error.message}")
            }
        }
    }
}
