package com.fataar.fixtures.consumerrules
class HiddenGreeting { fun message() = "reflection-ready" }
object ReflectionProbe {
    fun marker(): String {
        // Runtime concatenation exercises a name lookup instead of a class literal.
        val name = listOf("com", "fataar", "fixtures", "consumerrules", "HiddenGreeting").joinToString(".")
        val type = Class.forName(name)
        return type.getMethod("message").invoke(type.getDeclaredConstructor().newInstance()) as String
    }
}
