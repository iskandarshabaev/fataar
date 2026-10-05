package com.fataar.fixtures.androidcore
import com.fataar.fixtures.jvmcore.CoreText
object AndroidCore {
    fun marker(): String = CoreText.join(BuildConfig.FIXTURE_MARKER, JavaMarker.value())
}
inline fun <reified T> fixtureTypeName(): String = T::class.java.simpleName
