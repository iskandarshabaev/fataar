package com.fataar.gradle

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.*
import java.io.File
import javax.tools.ToolProvider

class CompileResourceStubsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun exposesNestedSymbolsWithoutInliningPlaceholderIds() {
        val classes = CompileResourceStubs.generate("sdk", "int layout title 0x0\nint[] styleable Badge { 0x0 }\nint styleable Badge_text 0\n")
        assertEquals(setOf("sdk/R.class", "sdk/R\$layout.class", "sdk/R\$styleable.class"), classes.keys)
        val jar = archive(File(temporary.root, "compile.jar"), classes)
        val source = File(temporary.root, "Consumer.java").apply { writeText("class Consumer { int id() { return sdk.R.layout.title; } }") }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", jar.path, source.path))
        var fieldRead = false
        ClassReader(File(temporary.root, "Consumer.class").readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitFieldInsn(op: Int, owner: String, name: String, descriptor: String) {
                    if (op == Opcodes.GETSTATIC && owner == "sdk/R\$layout" && name == "title") fieldRead = true
                }
            }
        }, 0)
        assertTrue("Compile placeholder must never be inlined", fieldRead)
        classes.filterKeys { it != "sdk/R.class" }.values.forEach { bytes ->
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(a: Int, n: String, d: String, s: String?, v: Any?): FieldVisitor? {
                    assertEquals(0, a and Opcodes.ACC_FINAL); assertNull(v); return null
                }
            }, 0)
        }
    }
}
