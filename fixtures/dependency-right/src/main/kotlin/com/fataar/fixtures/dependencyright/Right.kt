package com.fataar.fixtures.dependencyright
import com.fataar.fixtures.dependencyleaf.Leaf
object Right { fun marker() = "right->" + Leaf.marker() }
