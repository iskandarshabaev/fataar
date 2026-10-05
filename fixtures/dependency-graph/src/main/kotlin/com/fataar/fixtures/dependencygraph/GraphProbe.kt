package com.fataar.fixtures.dependencygraph
import com.fataar.fixtures.dependencyleft.Left
import com.fataar.fixtures.dependencyright.Right
object GraphProbe { fun marker() = Left.marker() + "|" + Right.marker() }
