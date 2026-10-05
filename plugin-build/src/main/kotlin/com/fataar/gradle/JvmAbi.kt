package com.fataar.gradle

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.objectweb.asm.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

internal data class AbiClass(val name: String, val access: Int, val references: Map<String, Set<String>>, val parents: List<String>)
internal object JvmAbi {
    fun classes(file: File): List<ByteArray> {
        if (file.isDirectory) return file.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.readBytes() }.toList()
        return buildList {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    if (entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/versions/")) {
                        add(zip.getInputStream(entry).use { it.readBytes() })
                    } else if (file.extension == "aar" && (entry.name == "classes.jar" || entry.name.startsWith("libs/") && entry.name.endsWith(".jar"))) {
                        ZipInputStream(zip.getInputStream(entry)).use { nested ->
                            var item = nested.nextEntry
                            while (item != null) {
                                if (!item.isDirectory && item.name.endsWith(".class") && !item.name.startsWith("META-INF/versions/")) add(nested.readBytes())
                                item = nested.nextEntry
                            }
                        }
                    }
                }
            }
        }
    }

    fun read(bytes: ByteArray): AbiClass {
        val reader = ClassReader(bytes)
        val references = sortedMapOf<String, MutableSet<String>>()
        var access = reader.access
        fun types(origin: String, descriptor: String) {
            fun visit(type: Type) {
                when (type.sort) {
                    Type.OBJECT -> references.getOrPut(origin) { sortedSetOf() }.add(type.internalName)
                    Type.ARRAY -> visit(type.elementType)
                    Type.METHOD -> { type.argumentTypes.forEach(::visit); visit(type.returnType) }
                }
            }
            visit(Type.getType(descriptor))
        }
        fun signature(origin: String, text: String?, typeOnly: Boolean = false) {
            if (text == null) return
            fun visitor(): SignatureVisitor = object : SignatureVisitor(Opcodes.ASM9) {
                private var outer: String? = null
                override fun visitClassType(name: String) { outer = name; references.getOrPut(origin) { sortedSetOf() }.add(name) }
                override fun visitInnerClassType(name: String) { outer = outer + "$" + name; references.getOrPut(origin) { sortedSetOf() }.add(outer!!) }
                override fun visitArrayType() = visitor()
                override fun visitClassBound() = visitor()
                override fun visitInterfaceBound() = visitor()
                override fun visitSuperclass() = visitor()
                override fun visitInterface() = visitor()
                override fun visitParameterType() = visitor()
                override fun visitReturnType() = visitor()
                override fun visitExceptionType() = visitor()
                override fun visitTypeArgument(wildcard: Char) = visitor()
            }
            if (typeOnly) SignatureReader(text).acceptType(visitor()) else SignatureReader(text).accept(visitor())
        }
        fun exported(flags: Int) = flags and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 &&
            flags and Opcodes.ACC_SYNTHETIC == 0
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, flags: Int, name: String, generic: String?, superName: String?, interfaces: Array<out String>?) {
                val origin = reader.className
                references.getOrPut(origin) { sortedSetOf() }.apply { superName?.let(::add); interfaces?.let(::addAll) }
                signature(origin, generic)
            }
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, flags: Int) {
                if (name == reader.className) access = flags
            }
            override fun visitField(flags: Int, name: String, descriptor: String, generic: String?, value: Any?): FieldVisitor? {
                if (exported(flags)) { types(reader.className + "#" + name, descriptor); signature(reader.className + "#" + name, generic, true) }
                return null
            }
            override fun visitMethod(flags: Int, name: String, descriptor: String, generic: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (exported(flags)) {
                    val origin = reader.className + "#" + name + descriptor
                    types(origin, descriptor); signature(origin, generic)
                    exceptions?.let { references.getOrPut(origin) { sortedSetOf() }.addAll(it) }
                }
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return AbiClass(reader.className, access, references, listOfNotNull(reader.superName) + reader.interfaces)
    }

    fun missing(classes: List<AbiClass>, available: Set<String>): List<Pair<String, String>> =
        classes.filter { it.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 }
            .flatMap { type -> type.references.flatMap { (origin, refs) -> refs.filter { it !in available }.map { origin to it } } }
            .distinct().sortedWith(compareBy({ it.first }, { it.second }))

    fun inheritedApi(packaged: List<AbiClass>, available: Map<String, ByteArray>): List<AbiClass> {
        val seen = packaged.map { it.name }.toMutableSet()
        val queue = ArrayDeque<String>()
        packaged.filter { it.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 }
            .forEach { queue.addAll(it.parents) }
        return buildList {
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (!seen.add(name)) continue
                val bytes = available[name] ?: continue
                val type = read(bytes)
                // Public members inherited from a package-private base are also exposed.
                add(type.copy(access = type.access or Opcodes.ACC_PUBLIC))
                queue.addAll(type.parents)
            }
        }
    }
}
