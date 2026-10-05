package com.fataar.gradle

internal fun isEmbedScope(name: String): Boolean = name == "embed" ||
    name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*Embed"))
