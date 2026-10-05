package com.fataar.gradle

import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.MergingReport
import com.android.utils.ILogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.GradleException
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.w3c.dom.Element

/** Conservative first merger: unsupported payload fails explicitly rather than being dropped. */
internal object AarMerger {
    private const val METADATA = "META-INF/com/android/build/gradle/aar-metadata.properties"
    private data class Input(val owner: String, val entries: Map<String, ByteArray>, val namespace: String?)
    private fun fail(code: String, message: String): Nothing = throw GradleException("FATAAR_$code: $message")

    internal fun readZip(bytes: ByteArray, owner: String): Map<String, ByteArray> {
        val result = sortedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.startsWith('/') || '\\' in name || name.split('/').any { it == ".." || it == "." })
                    fail("INVALID_ARCHIVE", "$owner: unsafe entry $name")
                if (!entry.isDirectory && result.put(name, zip.readBytes()) != null)
                    fail("INVALID_ARCHIVE", "$owner: duplicate entry $name")
            }
        }
        return result
    }

    internal fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { output ->
            entries.toSortedMap().forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                output.write(bytes)
                output.closeEntry()
            }
        }
    }.toByteArray()

    internal fun xml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    fun merge(host: File, embedded: List<MergeInput>, output: File, scratch: File, externalApi: List<MergeInput> = emptyList(), prefabPackageName: String = "sdk",
              nativeAbis: Set<String> = emptySet(), externalRuntime: List<MergeInput> = emptyList(), resourceWinners: Map<String, String> = emptyMap()): String {
        fun input(owner: String, file: File): Input {
            val entries = readZip(file.readBytes(), owner)
            return when (file.extension) {
                "aar" -> {
                    val manifest = entries["AndroidManifest.xml"] ?: fail("INVALID_AAR", "$owner: missing manifest")
                    val namespace = xml(manifest).getAttribute("package")
                    if (namespace.isBlank()) fail("INVALID_AAR", "$owner: missing manifest package")
                    Input(owner, entries, namespace)
                }
                "jar" -> Input(owner, mapOf("classes.jar" to file.readBytes()), null)
                else -> fail("UNSUPPORTED_ARTIFACT", "$owner: ${file.name}; expected AAR or JAR")
            }
        }
        val rawInputs = listOf(input("sdk", host)) + embedded.sortedBy { it.owner }.map { input(it.owner, it.file) }
        val mergedResources = ResourceMerge.merge(rawInputs.map { ResourceMerge.Input(it.owner, it.entries) }, resourceWinners)
        val inputs = rawInputs.mapIndexed { index, item -> item.copy(entries = mergedResources[index]) }
        val namespace = inputs.first().namespace!!
        // External libraries remain separate artifacts. Index only their own resources/classes;
        // copying their payload would duplicate what Maven consumers resolve transitively.
        val external = sortedMapOf<String, BindingResourceOwner>()
        externalApi.sortedBy { it.owner }.forEach { artifact ->
            val item = input(artifact.owner, artifact.file)
            val resourceNamespace = item.namespace ?: return@forEach
            if (resourceNamespace in inputs.mapNotNull { it.namespace })
                fail("NAMESPACE_CONFLICT", "$resourceNamespace is both embedded and external (${item.owner})")
            val classes = item.entries.filterKeys { it == "classes.jar" || (it.startsWith("libs/") && it.endsWith(".jar")) }
                .flatMap { (path, bytes) -> readZip(bytes, item.owner + "/" + path).keys.filter { it.endsWith(".class") } }.toSet()
            val symbols = item.entries["R.txt"]?.decodeToString().orEmpty().lineSequence().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(' ', limit = 4)
                if (parts.size != 4) fail("INVALID_SYMBOL", "${item.owner}: $line")
                parts[1] + "/" + parts[2]
            }.toSet()
            val previous = external.put(resourceNamespace, BindingResourceOwner(item.owner, classes, symbols))
            if (previous != null) fail("NAMESPACE_CONFLICT", "$resourceNamespace: ${previous.owner} and ${item.owner}")
        }
        fun minSdk(item: Input): Int {
            val manifest = item.entries["AndroidManifest.xml"] ?: return 1
            val usesSdk = xml(manifest).getElementsByTagName("uses-sdk").item(0) as? Element ?: return 1
            val value = usesSdk.getAttributeNS("http://schemas.android.com/apk/res/android", "minSdkVersion")
            return if (value.isEmpty()) 1 else value.toIntOrNull()
                ?: fail("UNSUPPORTED_MIN_SDK", "${item.owner}: $value")
        }
        val hostMinSdk = minSdk(inputs.first())
        inputs.drop(1).forEach {
            if (minSdk(it) > hostMinSdk) fail("MIN_SDK_CONFLICT", "${it.owner} requires ${minSdk(it)}, SDK declares $hostMinSdk")
        }
        val namespaces = inputs.mapNotNull { it.namespace }.toSet()
        val outputEntries = sortedMapOf<String, ByteArray>()
        val owners = mutableMapOf<String, String>()
        val jarEntries = sortedMapOf<String, ByteArray>()
        val jarOwners = mutableMapOf<String, String>()
        val resourceOwners = mutableMapOf<String, String>()
        val symbols = sortedMapOf<String, String>()
        val publicSymbols = sortedSetOf<String>()
        val rules = mutableListOf<String>()
        val metadata = sortedMapOf<String, String>()
        val services = sortedMapOf<String, MutableSet<String>>()
        var classCount = 0
        var rewritten = 0
        val classBytes = mutableListOf<Pair<String, ByteArray>>()

        fun add(entries: MutableMap<String, ByteArray>, contributors: MutableMap<String, String>,
                path: String, bytes: ByteArray, owner: String, classFile: Boolean = false) {
            val previous = entries[path]
            if (previous != null && (classFile || !previous.contentEquals(bytes)))
                fail(if (classFile) "DUPLICATE_CLASS" else "PAYLOAD_CONFLICT", "$path: ${contributors[path]} and $owner")
            entries[path] = bytes
            contributors.putIfAbsent(path, owner)
        }
        fun resource(key: String, owner: String) {
            val old = resourceOwners.putIfAbsent(key, owner)
            if (old != null) fail("RESOURCE_CONFLICT", "$key: $old and $owner; explicit overlays are not supported yet")
        }
        inputs.forEachIndexed { index, item ->
            val sourceSymbols = item.entries["R.txt"]?.decodeToString().orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            sourceSymbols.forEach { line ->
                val parts = line.split(' ', limit = 4)
                if (parts.size != 4) fail("INVALID_SYMBOL", "${item.owner}: $line")
                val key = parts[1] + "/" + parts[2]
                val old = symbols.putIfAbsent(key, line)
                if (old != null && old != line) fail("SYMBOL_CONFLICT", "$key: incompatible symbol definitions")
            }
            val explicitPublic = item.entries["public.txt"]?.decodeToString()?.trim().orEmpty()
            if (explicitPublic.isNotEmpty()) publicSymbols += explicitPublic.lines()
            else sourceSymbols.filter { !it.startsWith("int[]") && !it.startsWith("int styleable ") }.forEach {
                val parts = it.split(' ')
                publicSymbols += parts[1] + " " + parts[2]
            }
            item.entries.forEach { (path, bytes) ->
                when {
                    path == "AndroidManifest.xml" || path == "R.txt" || path == "public.txt" -> Unit
                    path == "classes.jar" || (path.startsWith("libs/") && path.endsWith(".jar")) -> {
                        readZip(bytes, item.owner + "/" + path).forEach { (entry, content) ->
                            when {
                                entry == "META-INF/MANIFEST.MF" -> Unit
                                entry.startsWith("META-INF/") && entry.substringAfterLast('.').uppercase() in setOf("SF", "RSA", "DSA", "EC") ->
                                    fail("UNSUPPORTED_SIGNED_JAR", "${item.owner}: $entry")
                                entry.startsWith("META-INF/versions/") || entry == "module-info.class" ->
                                    fail("UNSUPPORTED_MULTI_RELEASE_JAR", "${item.owner}: $entry")
                                entry.endsWith(".class") -> {
                                    // Definitions are collected before remapping so duplicates never disappear silently.
                                    add(jarEntries, jarOwners, entry, content, item.owner, true)
                                    classBytes += item.owner to content
                                    classCount++
                                }
                                entry.startsWith("META-INF/services/") -> services.getOrPut(entry) { sortedSetOf() }.addAll(
                                    content.decodeToString().lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList())
                                entry.endsWith(".kotlin_module") -> add(jarEntries, jarOwners,
                                    "META-INF/fataar_${index}_" + entry.substringAfterLast('/'), content, item.owner)
                                else -> add(jarEntries, jarOwners, entry, content, item.owner)
                            }
                        }
                    }
                    path.startsWith("prefab/") -> Unit // Consolidated as one package after all module owners are known.
                    path == "proguard.txt" -> rules += "# ${item.owner}\n" + bytes.decodeToString()
                    path == METADATA -> {
                        val values = Properties().apply { load(ByteArrayInputStream(bytes)) }
                        values.stringPropertyNames().forEach { key ->
                            val value = values.getProperty(key)
                            val old = metadata[key]
                            metadata[key] = when {
                                key in setOf("minCompileSdk", "minCompileSdkExtension") -> maxOf(old?.toInt() ?: 0, value.toInt()).toString()
                                old == null || old == value -> value
                                else -> fail("METADATA_CONFLICT", "$key: $old vs $value (${item.owner})")
                            }
                        }
                    }
                    path.startsWith("res/values") && path.endsWith(".xml") -> {
                        val root = xml(bytes)
                        if (root.tagName != "resources") fail("INVALID_RESOURCE", "${item.owner}: $path")
                        for (position in 0 until root.childNodes.length) {
                            val child = root.childNodes.item(position) as? Element ?: continue
                            if (child.tagName == "public" || child.tagName == "eat-comment" || child.tagName == "skip") continue
                            val type = when (child.tagName) {
                                "item" -> child.getAttribute("type")
                                "string-array", "integer-array" -> "array"
                                else -> child.tagName
                            }
                            val name = child.getAttribute("name")
                            if (name.isBlank()) fail("INVALID_RESOURCE", "${item.owner}: unnamed $type in $path")
                            val qualifier = path.substringBeforeLast('/').removePrefix("res/values").removePrefix("-")
                            resource("$qualifier/$type/$name", item.owner)
                            // A styleable split between qualifiers/modules can change inlined indices.
                            if (type == "declare-styleable") {
                                resource("styleable/$name", item.owner)
                                val attributes = child.getElementsByTagName("attr")
                                for (attributeIndex in 0 until attributes.length) {
                                    val attr = attributes.item(attributeIndex) as Element
                                    val attrName = attr.getAttribute("name")
                                    if (':' !in attrName && (attr.hasAttribute("format") || attr.getElementsByTagName("enum").length > 0 || attr.getElementsByTagName("flag").length > 0))
                                        resource("$qualifier/attr/$attrName", item.owner)
                                }
                            }
                        }
                        val target = path.substringBeforeLast('/') + "/fataar_${index}_" + path.substringAfterLast('/')
                        add(outputEntries, owners, target, bytes, item.owner)
                    }
                    path.startsWith("res/") -> {
                        val folder = path.removePrefix("res/").substringBefore('/')
                        val qualifier = folder.substringAfter('-', "")
                        resource("$qualifier/${folder.substringBefore('-')}/" + path.substringAfterLast('/').substringBefore('.'), item.owner)
                        add(outputEntries, owners, path, bytes, item.owner)
                    }
                    path.startsWith("assets/") || path.startsWith("jni/") || path == "annotations.zip" ->
                        add(outputEntries, owners, path, bytes, item.owner)
                    path.startsWith("aidl/") && path.endsWith(".aidl") ->
                        add(outputEntries, owners, path, bytes, item.owner)
                    path.startsWith("data-binding/") && (path.endsWith("-br.bin") || path.endsWith("-setter_store.json")) ->
                        add(outputEntries, owners, path, DataBindingMetadata.rewrite(path, bytes, item.owner, namespace, namespaces, external), item.owner)
                    path.startsWith("data-binding-base-class-log/") && path.endsWith("-binding_classes.json") ->
                        add(outputEntries, owners, path, DataBindingMetadata.rewrite(path, bytes, item.owner, namespace, namespaces, external), item.owner)
                    else -> fail("UNSUPPORTED_PAYLOAD", "${item.owner}: $path; add explicit merge support before embedding this artifact")
                }
            }
        }
        val prefabSources = inputs.map { PrefabMerger.Source(it.owner, it.entries) }
        val apiSources = externalApi.filter { it.file.extension == "aar" }.map { PrefabMerger.Source(it.owner, readZip(it.file.readBytes(), it.owner)) }
        outputEntries.putAll(PrefabMerger.merge(prefabSources, prefabPackageName, apiSources))
        val runtimeSources = externalRuntime.filter { it.file.extension == "aar" }.map { PrefabMerger.Source(it.owner, readZip(it.file.readBytes(), it.owner)) }
        NativePayload.validate(outputEntries, jarEntries, hostMinSdk, nativeAbis, runtimeSources)
        DataBindingMetadata.validate(outputEntries, jarEntries.keys, symbols.keys, owners, external)
        val targetPrefix = namespace.replace('.', '/') + "/R"
        val prefixes = namespaces.map { it.replace('.', '/') + "/R" }.toSet()
        fun mapped(name: String): String {
            val prefix = name.substringBefore('$')
            return if (prefix in prefixes) targetPrefix + name.removePrefix(prefix) else name
        }
        val remapper = object : Remapper(Opcodes.ASM9) {
            override fun map(internalName: String): String = mapped(internalName)
        }
        classBytes.forEach { (owner, bytes) ->
            val reader = ClassReader(bytes)
            if (reader.className.substringBefore('$') in prefixes)
                fail("UNSUPPORTED_PACKAGED_R", "$owner: ${reader.className}; library R must be generated by the consumer")
            val writer = ClassWriter(0)
            reader.accept(ClassRemapper(writer, remapper), 0)
            val transformed = writer.toByteArray()
            if (!bytes.contentEquals(transformed)) rewritten++
            ClassReader(transformed).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitFieldInsn(opcode: Int, fieldOwner: String, name: String, descriptor: String) {
                            if (fieldOwner.startsWith(targetPrefix + "$")) {
                                val key = fieldOwner.substringAfterLast('$') + "/" + name
                                if (key !in symbols) fail("MISSING_RESOURCE_SYMBOL", "${reader.className} references $key ($owner)")
                            }
                        }
                    }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            jarEntries[reader.className + ".class"] = transformed
        }
        services.forEach { (path, providers) ->
            providers.forEach { name ->
                if (name.replace('.', '/') + ".class" !in jarEntries)
                    fail("MISSING_SERVICE_PROVIDER", "$path: $name is absent from embedded classes")
            }
            jarEntries[path] = (providers.joinToString("\n") + "\n").toByteArray()
        }
        outputEntries["classes.jar"] = zip(jarEntries)
        outputEntries["R.txt"] = (symbols.values.joinToString("\n") + "\n").toByteArray()
        if (publicSymbols.isNotEmpty()) outputEntries["public.txt"] = (publicSymbols.joinToString("\n") + "\n").toByteArray()
        if (rules.isNotEmpty()) outputEntries["proguard.txt"] = (rules.joinToString("\n\n") + "\n").toByteArray()
        if (metadata.isNotEmpty()) outputEntries[METADATA] = metadata.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }.toByteArray()
        scratch.mkdirs()
        val manifests = inputs.filter { it.namespace != null }.mapIndexed { index, item ->
            item.owner to File(scratch, "manifest-$index-${item.owner.replace(Regex("[^a-zA-Z0-9_.-]"), "_")}.xml")
                .apply { writeBytes(item.entries.getValue("AndroidManifest.xml")) }
        }
        val messages = mutableListOf<String>()
        val logger = object : ILogger {
            override fun error(t: Throwable?, msg: String?, vararg args: Any?) { messages += msg?.format(*args).orEmpty() }
            override fun warning(msg: String, vararg args: Any?) { messages += msg.format(*args) }
            override fun info(msg: String, vararg args: Any?) = Unit
            override fun verbose(msg: String, vararg args: Any?) = Unit
        }
        val result = ManifestMerger2.newMerger(manifests.first().second, logger, ManifestMerger2.MergeType.LIBRARY)
            // These are already-built AAR manifests, not deprecated source-manifest SDK settings.
            .withFeatures(ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING)
            .addManifestProviders(manifests.drop(1).map { (owner, file) -> object : com.android.manifmerger.ManifestProvider {
                override fun getManifest(): File = file
                override fun getName(): String = owner
            } }).merge()
        if (result.result == MergingReport.Result.ERROR)
            fail("MANIFEST_CONFLICT", "inputs: ${manifests.joinToString { it.first }}\n${result.reportString}")
        val mergedManifest = result.getMergedDocument(MergingReport.MergedManifestKind.MERGED)!!.toByteArray()
        // Android's merger keys providers by class name, not authority. Distinct providers
        // can therefore produce a successful merge with an unusable installation.
        val authorityOwners = mutableMapOf<String, String>()
        val providers = xml(mergedManifest).getElementsByTagName("provider")
        for (index in 0 until providers.length) {
            val provider = providers.item(index) as Element
            // queries/provider declares package visibility, not an installed component.
            if (provider.parentNode.nodeName != "application") continue
            if (provider.getAttributeNS("http://schemas.android.com/tools", "node") in setOf("remove", "removeAll")) continue
            val name = provider.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
            provider.getAttributeNS("http://schemas.android.com/apk/res/android", "authorities")
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { authority ->
                    val previous = authorityOwners.putIfAbsent(authority, name)
                    if (previous != null && previous != name)
                        fail("DUPLICATE_PROVIDER_AUTHORITY", "$authority: $previous and $name; inputs: ${manifests.joinToString { it.first }}")
                }
        }
        outputEntries["AndroidManifest.xml"] = mergedManifest
        output.parentFile.mkdirs()
        output.writeBytes(zip(outputEntries))
        return buildString {
            appendLine("format=1\npackaging=merged-aar\nnamespace=$namespace\nclasses=$classCount\nrewrittenClasses=$rewritten")
            inputs.forEach { appendLine("input=${it.owner}") }
            appendLine("result=PASS")
        }
    }
}
