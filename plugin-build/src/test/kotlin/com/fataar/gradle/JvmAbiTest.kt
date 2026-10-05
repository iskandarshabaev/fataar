package com.fataar.gradle
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class JvmAbiTest {
    @Test fun inheritedMethodCanExposeTypeAbsentFromExternalApi() {
        val sdk = JvmAbi.read(classBytes("sdk/Api", "outside/Base"))
        val base = classBytes("outside/Base") {
            visitMethod(Opcodes.ACC_PUBLIC, "value", "()Lmissing/Type;", null, null).visitEnd()
        }
        val available = mapOf("outside/Base" to base, "java/lang/Object" to classBytes("java/lang/Object"))
        val inherited = JvmAbi.inheritedApi(listOf(sdk), available)
        assertTrue(JvmAbi.missing(listOf(sdk) + inherited, available.keys + "sdk/Api")
            .any { it.first.contains("outside/Base#value") && it.second == "missing/Type" })
    }
    @Test fun checksInheritanceArraysGenericsAndCheckedExceptions() {
        val bytes = classBytes("sdk/Api", "outside/Base") {
            visitField(Opcodes.ACC_PUBLIC, "items", "Ljava/util/List;", "Ljava/util/List<[Loutside/Item;>;", null).visitEnd()
            visitMethod(Opcodes.ACC_PROTECTED, "read", "([Loutside/Argument;)Loutside/Result;", null, arrayOf("outside/Problem")).visitEnd()
        }
        val missing = JvmAbi.missing(listOf(JvmAbi.read(bytes)), setOf("java/util/List")).map { it.second }.toSet()
        assertEquals(setOf("outside/Base", "outside/Item", "outside/Argument", "outside/Result", "outside/Problem"), missing)
    }
    @Test fun privateMembersAndSyntheticCompilerConstructorsAreNotPublicApi() {
        val bytes = classBytes("sdk/Api") {
            visitField(Opcodes.ACC_PRIVATE, "secret", "Loutside/Secret;", null, null).visitEnd()
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, "<init>", "(Loutside/CompilerMarker;)V", null, null).visitEnd()
        }
        assertTrue(JvmAbi.missing(listOf(JvmAbi.read(bytes)), setOf("java/lang/Object")).isEmpty())
    }
    @Test fun boundsAndNestedGenericTypesAreRetained() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sdk/Api",
            "<T:Loutside/Bound;>Ljava/lang/Object;", "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "nested", "Loutside/Outer\$Inner;",
            "Loutside/Outer<Ljava/lang/String;>.Inner<Loutside/Item;>;", null).visitEnd()
        writer.visitEnd()
        val missing = JvmAbi.missing(listOf(JvmAbi.read(writer.toByteArray())), setOf("java/lang/Object", "java/lang/String")).map { it.second }.toSet()
        assertEquals(setOf("outside/Bound", "outside/Outer", "outside/Outer\$Inner", "outside/Item"), missing)
    }
    @Test fun privateNestedClassDoesNotExposeItsSuperclass() {
        val bytes = classBytes("sdk/Api\$Hidden", "outside/Base") {
            visitInnerClass("sdk/Api\$Hidden", "sdk/Api", "Hidden", Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC)
        }
        assertTrue(JvmAbi.missing(listOf(JvmAbi.read(bytes)), emptySet()).isEmpty())
    }
}
internal fun classBytes(name: String, parent: String = "java/lang/Object", members: ClassWriter.() -> Unit = {}): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null)
    writer.members()
    writer.visitEnd()
    return writer.toByteArray()
}
