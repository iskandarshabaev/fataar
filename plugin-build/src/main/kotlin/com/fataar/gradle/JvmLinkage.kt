package com.fataar.gradle

import org.objectweb.asm.*

/** Validate instructions in packaged classes against the selected runtime, not the compile classpath. */
internal object JvmLinkage {
    private data class Member(val name: String, val descriptor: String, val access: Int, val owner: String)
    private data class Definition(val name: String, val access: Int, val parents: List<String>, val fields: List<Member>, val methods: List<Member>)
    private data class Reference(val origin: String, val owner: String, val name: String? = null, val descriptor: String = "", val opcode: Int = 0, val field: Boolean = false)
    fun validate(packaged: List<ByteArray>, available: Map<String, ByteArray>, deferred: Set<String> = emptySet()): List<String> {
        val definitions = available.mapValues { (_, bytes) ->
            val reader = ClassReader(bytes)
            val fields = mutableListOf<Member>(); val methods = mutableListOf<Member>()
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? { fields += Member(n, d, a, reader.className); return null }
                override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): MethodVisitor? { methods += Member(n, d, a, reader.className); return null }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            Definition(reader.className, reader.access, listOfNotNull(reader.superName) + reader.interfaces, fields, methods)
        }
        val refs = linkedSetOf<Reference>()
        fun type(origin: String, descriptor: String) {
            fun add(t: Type) {
                when (t.sort) {
                    Type.OBJECT -> refs += Reference(origin, t.internalName)
                    Type.ARRAY -> add(t.elementType)
                    Type.METHOD -> { t.argumentTypes.forEach(::add); add(t.returnType) }
                }
            }
            add(Type.getType(descriptor))
        }
        fun owner(origin: String, name: String) { if (name.startsWith('[')) type(origin, name) else refs += Reference(origin, name) }
        fun handle(origin: String, h: Handle) {
            val field = h.tag in Opcodes.H_GETFIELD..Opcodes.H_PUTSTATIC
            val opcode = when(h.tag) {
                Opcodes.H_GETSTATIC -> Opcodes.GETSTATIC; Opcodes.H_PUTSTATIC -> Opcodes.PUTSTATIC
                Opcodes.H_GETFIELD -> Opcodes.GETFIELD; Opcodes.H_PUTFIELD -> Opcodes.PUTFIELD
                Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC; Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE
                Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL
                else -> Opcodes.INVOKEVIRTUAL
            }
            owner(origin, h.owner); type(origin, h.desc)
            refs += Reference(origin, h.owner, h.name, h.desc, opcode, field)
        }
        fun constant(origin: String, value: Any?) {
            when (value) {
                is Type -> type(origin, value.descriptor)
                is Handle -> handle(origin, value)
                is ConstantDynamic -> {
                    type(origin, value.descriptor); handle(origin, value.bootstrapMethod)
                    for (i in 0 until value.bootstrapMethodArgumentCount) constant(origin, value.getBootstrapMethodArgument(i))
                }
            }
        }
        packaged.forEach { bytes ->
            val reader = ClassReader(bytes)
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(v: Int, a: Int, n: String, s: String?, parent: String?, interfaces: Array<out String>?) {
                    parent?.let { owner(n, it) }; interfaces?.forEach { owner(n, it) }
                }
                override fun visitField(a: Int, n: String, d: String, s: String?, value: Any?): FieldVisitor? {
                    type(reader.className + "#" + n, d); constant(reader.className, value); return null
                }
                override fun visitMethod(a: Int, n: String, d: String, s: String?, exceptions: Array<out String>?): MethodVisitor {
                    val origin = reader.className + "#" + n + d
                    type(origin, d); exceptions?.forEach { owner(origin, it) }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitTypeInsn(op: Int, name: String) = owner(origin, name)
                        override fun visitMultiANewArrayInsn(d: String, dimensions: Int) = type(origin, d)
                        override fun visitFieldInsn(op: Int, o: String, n: String, d: String) {
                            owner(origin, o); type(origin, d); refs += Reference(origin, o, n, d, op, true)
                        }
                        override fun visitMethodInsn(op: Int, o: String, n: String, d: String, itf: Boolean) {
                            owner(origin, o); type(origin, d); refs += Reference(origin, o, n, d, op)
                        }
                        override fun visitLdcInsn(value: Any?) = constant(origin, value)
                        override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, t: String?) { t?.let { owner(origin, it) } }
                        override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any) {
                            type(origin, d)
                            // These javac bootstraps are implemented by D8's desugaring pipeline.
                            val desugared = when (bsm.owner) {
                                "java/lang/invoke/LambdaMetafactory" -> bsm.name in setOf("metafactory", "altMetafactory")
                                "java/lang/invoke/StringConcatFactory" -> bsm.name in setOf("makeConcat", "makeConcatWithConstants")
                                "java/lang/runtime/ObjectMethods" -> bsm.name == "bootstrap"
                                else -> false
                            }
                            if (!desugared) handle(origin, bsm)
                            args.forEach { constant(origin, it) }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        fun member(name: String, ref: Reference, visited: MutableSet<String> = mutableSetOf()): Member? {
            if (!visited.add(name)) return null
            val definition = definitions[name] ?: return null
            val entries = if (ref.field) definition.fields else definition.methods
            entries.firstOrNull { it.name == ref.name && it.descriptor == ref.descriptor }?.let { return it }
            if (!ref.field && name in setOf("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle")) {
                entries.firstOrNull { it.name == ref.name && it.access and Opcodes.ACC_NATIVE != 0 && it.access and Opcodes.ACC_VARARGS != 0 }?.let { return it }
            }
            if (ref.name == "<init>") return null // Constructors are never inherited.
            val parents = if (ref.field) definition.parents.drop(1) + definition.parents.take(1)
                else definition.parents.filter { it != "java/lang/Object" || definition.access and Opcodes.ACC_INTERFACE == 0 }
            parents.forEach { parent -> member(parent, ref, visited)?.let { return it } }
            if (!ref.field && definition.access and Opcodes.ACC_INTERFACE != 0)
                definitions["java/lang/Object"]?.methods?.firstOrNull {
                    it.name == ref.name && it.descriptor == ref.descriptor && it.access and Opcodes.ACC_PUBLIC != 0 && it.access and Opcodes.ACC_STATIC == 0
                }?.let { return it }
            return null
        }
        val nests = available.mapValues { (name, bytes) ->
            var host = name
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitNestHost(nestHost: String) { host = nestHost }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            host
        }
        fun subtype(child: String, parent: String, seen: MutableSet<String> = mutableSetOf()): Boolean =
            child == parent || seen.add(child) && definitions[child]?.parents.orEmpty().any { subtype(it, parent, seen) }
        fun accessible(origin: String, member: Member): Boolean {
            val caller = origin.substringBefore('#')
            val flags = member.access
            return when {
                flags and Opcodes.ACC_PUBLIC != 0 -> true
                flags and Opcodes.ACC_PRIVATE != 0 -> caller == member.owner || nests[caller] != null && nests[caller] == nests[member.owner]
                caller.substringBeforeLast('/', "") == member.owner.substringBeforeLast('/', "") -> true
                flags and Opcodes.ACC_PROTECTED != 0 -> subtype(caller, member.owner)
                else -> false
            }
        }
        return refs.mapNotNull { ref ->
            val target = ref.owner
            if (target in deferred) return@mapNotNull null
            if (ref.name == null) {
                if (target !in definitions) "FATAAR_LINKAGE_MISSING_CLASS: ${ref.origin} references $target" else null
            } else if (target.startsWith('[') && ref.name == "clone" && ref.descriptor == "()Ljava/lang/Object;") null
            else {
                val actualOwner = if (target.startsWith('[')) "java/lang/Object" else target
                if (actualOwner !in definitions) return@mapNotNull null // The class reference reports this once.
                val found = member(actualOwner, ref)
                val label = if (ref.field) "FIELD" else "METHOD"
                when {
                    found == null -> "FATAAR_LINKAGE_MISSING_$label: ${ref.origin} references $target#${ref.name}${ref.descriptor}"
                    (found.access and Opcodes.ACC_STATIC != 0) != (ref.opcode in setOf(Opcodes.INVOKESTATIC, Opcodes.GETSTATIC, Opcodes.PUTSTATIC)) ->
                        "FATAAR_LINKAGE_STATIC_MISMATCH: ${ref.origin} references $target#${ref.name}${ref.descriptor} with incompatible static/instance access"
                    !accessible(ref.origin, found) -> "FATAAR_LINKAGE_ACCESS: ${ref.origin} cannot access $target#${ref.name}${ref.descriptor}"
                    ref.opcode == Opcodes.INVOKEINTERFACE && definitions[actualOwner]!!.access and Opcodes.ACC_INTERFACE == 0 ||
                        ref.opcode == Opcodes.INVOKEVIRTUAL && definitions[actualOwner]!!.access and Opcodes.ACC_INTERFACE != 0 ->
                        "FATAAR_LINKAGE_KIND: ${ref.origin} references $target with an incompatible class/interface invocation"
                    else -> null
                }
            }
        }.distinct().sorted()
    }
}
