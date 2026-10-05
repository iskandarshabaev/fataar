package com.fataar.gradle

import org.gradle.api.GradleException
import org.objectweb.asm.*

/** Validate delivered loader dependencies and the ABI/binary contract exported by Prefab. */
internal object NativePayload {
    fun validate(entries: Map<String, ByteArray>, classes: Map<String, ByteArray>, minSdk: Int,
                 expectedAbis: Set<String> = emptySet(), external: List<PrefabMerger.Source> = emptyList()) {
        fun fail(code: String, message: String): Nothing = throw GradleException("FATAAR_NATIVE_$code: $message")
        val libraries = linkedMapOf<Pair<String, String>, NativeElf.Library>()
        fun index(source: Map<String, ByteArray>, owner: String) {
            source.filterKeys { it.startsWith("jni/") }.forEach { (path, bytes) ->
                val parts = path.split('/')
                if (parts.size != 3 || !parts[2].startsWith("lib") || !parts[2].endsWith(".so"))
                    fail("PAYLOAD", "$owner: unsupported JNI path $path")
                val elf = NativeElf.inspect(bytes, parts[1], "$owner:$path")
                if (elf.soname != null && elf.soname != parts[2]) fail("SONAME", "$owner:$path has SONAME ${elf.soname}")
                val key = parts[1] to parts[2]
                if (libraries.putIfAbsent(key, elf) != null && owner != "SDK")
                    fail("DELIVERY_CONFLICT", "$path is both packaged and external or appears in multiple external artifacts ($owner)")
            }
        }
        index(entries, "SDK")
        external.forEach { index(it.entries, it.owner) }
        fun available(abi: String, name: String, origin: String) {
            val introduced = platformNativeLibraries[name]
            if (introduced != null) {
                if (introduced > minSdk) fail("MIN_SDK", "$origin needs platform $name (API $introduced), SDK minSdk=$minSdk")
            } else if (abi to name !in libraries) fail("MISSING_LIBRARY", "$origin needs jni/$abi/$name; add the owning module to embed or declare its external runtime dependency")
        }
        libraries.forEach { (key, elf) -> elf.needed.forEach { available(key.first, it, "${key.first}/${key.second}") } }
        val loads = sortedMapOf<String, MutableSet<String>>()
        classes.filterKeys { it.endsWith(".class") }.forEach { (path, bytes) ->
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        var literal: String? = null
                        override fun visitLdcInsn(value: Any) { literal = value as? String }
                        override fun visitInsn(opcode: Int) { literal = null }
                        override fun visitVarInsn(opcode: Int, variable: Int) { literal = null }
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) { literal = null }
                        override fun visitTypeInsn(opcode: Int, type: String) { literal = null }
                        override fun visitIntInsn(opcode: Int, operand: Int) { literal = null }
                        override fun visitJumpInsn(opcode: Int, label: Label) { literal = null }
                        override fun visitIincInsn(variable: Int, increment: Int) { literal = null }
                        override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrap: Handle, vararg arguments: Any) { literal = null }
                        override fun visitTableSwitchInsn(min: Int, max: Int, default: Label, vararg labels: Label) { literal = null }
                        override fun visitLookupSwitchInsn(default: Label, keys: IntArray, labels: Array<out Label>) { literal = null }
                        override fun visitMultiANewArrayInsn(descriptor: String, dimensions: Int) { literal = null }
                        override fun visitMethodInsn(opcode: Int, owner: String, method: String, descriptor: String, isInterface: Boolean) {
                            if (opcode == Opcodes.INVOKESTATIC && owner == "java/lang/System" && method == "loadLibrary" && descriptor == "(Ljava/lang/String;)V")
                                literal?.let { loads.getOrPut("lib$it.so") { sortedSetOf() } += "$path#$name" }
                            literal = null
                        }
                    }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        val abis = if (expectedAbis.isEmpty()) libraries.keys.map { it.first }.toSet() else expectedAbis
        loads.forEach { (name, origins) ->
            if (abis.isEmpty() && name !in platformNativeLibraries) fail("MISSING_LIBRARY", "$origins loads $name but no native artifacts/ABIs were provided")
            abis.forEach { available(it, name, origins.joinToString()) }
        }
        val modulePrefix = "prefab/modules/"
        val modules = entries.keys.filter { it.startsWith(modulePrefix) }.map { it.removePrefix(modulePrefix).substringBefore('/') }.toSet()
        modules.forEach { module ->
            val prefix = "$modulePrefix$module/"
            val owner = "Prefab module $module"
            val metadata = PrefabMerger.json(entries.getValue(prefix + "module.json"), owner)
            val binaryName = metadata.getAsJsonObject("android")?.get("library_name")?.takeUnless { it.isJsonNull }?.asString
                ?: metadata.get("library_name")?.takeUnless { it.isJsonNull }?.asString ?: "lib$module"
            if (binaryName.isBlank() || '/' in binaryName || '\\' in binaryName) PrefabMerger.fail(owner, "invalid library_name $binaryName")
            val records = entries.keys.filter { it.startsWith(prefix + "libs/") && it.endsWith("/abi.json") }
            if (records.isEmpty()) {
                if (entries.keys.any { it.startsWith(prefix + "libs/") }) PrefabMerger.fail(owner, "binaries have no ABI metadata")
                if (entries.keys.none { it.startsWith(prefix + "include/") }) PrefabMerger.fail(owner, "header-only module has no headers")
            } else {
                val delivered = mutableSetOf<String>()
                val allowed = mutableSetOf<String>()
                records.forEach { path ->
                    val abi = PrefabMerger.json(entries.getValue(path), "$owner:$path")
                    try {
                        val name = abi.get("abi").asString
                        if (!delivered.add(name)) PrefabMerger.fail(owner, "duplicate ABI $name")
                        if (abi.get("api").asInt < 1) PrefabMerger.fail(owner, "invalid API level")
                        if (abi.get("api").asInt > minSdk) fail("MIN_SDK", "$owner requires API ${abi.get("api")}, SDK minSdk=$minSdk")
                        if (abi.get("ndk").asInt < 1 || abi.get("stl").asString !in setOf("none", "system", "c++_static", "c++_shared"))
                            PrefabMerger.fail(owner, "invalid NDK/STL metadata")
                        val static = abi.get("static")?.asBoolean ?: false
                        val libraryPath = path.substringBeforeLast('/') + "/$binaryName" + if (static) ".a" else ".so"
                        allowed += path; allowed += libraryPath
                        val bytes = entries[libraryPath] ?: fail("MISSING_LIBRARY", "$owner: absent $libraryPath")
                        if (static) NativeElf.inspectArchive(bytes, name, libraryPath)
                        else {
                            val elf = NativeElf.inspect(bytes, name, libraryPath)
                            if (elf.soname != null && elf.soname != "$binaryName.so") fail("SONAME", "$libraryPath has SONAME ${elf.soname}")
                            elf.needed.forEach { available(name, it, libraryPath) }
                            if (name to "$binaryName.so" !in libraries) fail("MISSING_LIBRARY", "$owner: missing runtime jni/$name/$binaryName.so")
                        }
                    } catch (error: RuntimeException) {
                        if (error is GradleException) throw error
                        PrefabMerger.fail(owner, "invalid abi metadata $path: ${error.message}")
                    }
                }
                entries.keys.filter { it.startsWith(prefix + "libs/") && it !in allowed }.firstOrNull()?.let {
                    PrefabMerger.fail(owner, "unaccounted binary payload $it")
                }
                if (expectedAbis.isNotEmpty() && !delivered.containsAll(expectedAbis))
                    fail("MISSING_ABI", "$owner missing ${expectedAbis - delivered}; expected $expectedAbis")
            }
        }
    }
}
