package com.fataar.gradle

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.*

class JvmLinkageTest {
    private fun klass(name: String, parent: String? = "java/lang/Object", body: ClassWriter.() -> Unit = {}): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null); body(); visitEnd()
    }.toByteArray()
    private fun caller(owner: String, name: String = "gone", desc: String = "()V", opcode: Int = Opcodes.INVOKESTATIC, field: Boolean = false) = klass("sdk/Caller") {
        visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "privateBody", "()V", null, null).apply {
            visitCode()
            if (field) visitFieldInsn(opcode, owner, name, desc) else visitMethodInsn(opcode, owner, name, desc, false)
            visitInsn(Opcodes.RETURN); visitMaxs(2, 0); visitEnd()
        }
    }
    private fun validate(code: ByteArray, vararg others: ByteArray): List<String> {
        val all = listOf(code, klass("java/lang/Object", null)) + others
        return JvmLinkage.validate(listOf(code), all.associateBy { ClassReader(it).className })
    }
    @Test fun diagnosesMissingClassMethodAndFieldInPrivateMethodBodies() {
        assertTrue(validate(caller("missing/Type")).any { it.contains("MISSING_CLASS") && it.contains("privateBody") })
        assertTrue(validate(caller("outside/Type"), klass("outside/Type")).any { it.contains("MISSING_METHOD") && it.contains("gone()V") })
        assertTrue(validate(caller("outside/Type", "field", "I", Opcodes.GETSTATIC, true), klass("outside/Type")).any { it.contains("MISSING_FIELD") })
    }
    @Test fun resolvesInheritedMembersAndDetectsStaticMismatchAndChangedDescriptors() {
        val base = klass("outside/Base") { visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "ok", "()V", null, null).visitEnd() }
        val child = klass("outside/Child", "outside/Base")
        assertTrue(validate(caller("outside/Child", "ok"), child, base).isEmpty())
        assertTrue(validate(caller("outside/Child", "ok", opcode = Opcodes.INVOKEVIRTUAL), child, base).single().contains("STATIC_MISMATCH"))
        assertTrue(validate(caller("outside/Child", "ok", "(I)V"), child, base).single().contains("MISSING_METHOD"))
        assertTrue(validate(caller("outside/Child", "<init>", opcode = Opcodes.INVOKESPECIAL), child, base).single().contains("MISSING_METHOD"))
    }
    @Test fun checksLambdaHandlesAndArrayComponentTypes() {
        val code = klass("sdk/Lambda") {
            visitMethod(Opcodes.ACC_PUBLIC, "lambda", "()V", null, null).apply {
                visitCode()
                visitInvokeDynamicInsn("call", "()V", Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                    Handle(Opcodes.H_INVOKESTATIC, "outside/Target", "removed", "()V", false))
                visitTypeInsn(Opcodes.ANEWARRAY, "missing/Element")
                visitInsn(Opcodes.RETURN); visitMaxs(2, 1); visitEnd()
            }
        }
        val failures = validate(code, klass("outside/Target"))
        assertTrue(failures.any { it.contains("Target#removed") })
        assertTrue(failures.any { it.contains("missing/Element") })
        assertFalse(failures.any { it.contains("LambdaMetafactory") })
        assertTrue(validate(caller("[I", "clone", "()Ljava/lang/Object;", Opcodes.INVOKEVIRTUAL)).isEmpty())
    }
    @Test fun rejectsPrivateAndPackageMembersButAcceptsProtectedInSubclass() {
        val hidden = klass("outside/Target") { visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "secret", "()V", null, null).visitEnd() }
        assertTrue(validate(caller("outside/Target", "secret"), hidden).single().contains("LINKAGE_ACCESS"))
        val base = klass("outside/Base") { visitMethod(Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC, "secret", "()V", null, null).visitEnd() }
        val code = klass("sdk/Child", "outside/Base") {
            visitMethod(Opcodes.ACC_PUBLIC, "call", "()V", null, null).apply {
                visitCode(); visitMethodInsn(Opcodes.INVOKESTATIC, "outside/Base", "secret", "()V", false)
                visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
            }
        }
        assertTrue(validate(code, base).isEmpty())
    }
}
