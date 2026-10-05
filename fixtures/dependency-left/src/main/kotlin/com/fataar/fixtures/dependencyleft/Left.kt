package com.fataar.fixtures.dependencyleft
import com.fataar.fixtures.dependencyleaf.Leaf
object Left { fun marker() = "left->" + Leaf.marker() }
