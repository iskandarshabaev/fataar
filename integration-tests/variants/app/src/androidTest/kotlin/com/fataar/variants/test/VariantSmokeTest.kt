package com.fataar.variants.test
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fataar.variants.sdk.VariantFacade
import com.fataar.variants.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class VariantSmokeTest {
    @Test fun chosenVariantHasCorrectCodeResourcesAssetsAndScopes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = VariantFacade.inspect(context)
        val color = BuildConfig.FLAVOR
        val sdkMode = if (BuildConfig.PUBLISHED_SDK) "release" else BuildConfig.BUILD_TYPE
        val childMode = if (sdkMode == "staging") "debug" else sdkMode
        val engine = if (color == "red") "modern" else "classic"
        assertEquals(color, values["sdk-color"]); assertEquals(sdkMode, values["sdk-mode"])
        assertEquals("flavor-$color:$childMode" + if (color == "red") ":leaf-red" else "", values["color"])
        assertEquals("engine-$engine:$childMode", values["engine"])
        assertEquals("base-ready", values["base"]); assertEquals("$color-only", values["branch"])
        assertEquals(if (color == "red" && sdkMode == "release") "release-only" else "none", values["extra"])
        assertEquals("build-$sdkMode" + if (sdkMode == "staging") ":staging-only" else "", values["build"])
        assertEquals("host-$color", values["host-resource"]); assertEquals("color-$color", values["child-resource"])
        assertEquals(color, values["asset-color"]); assertEquals(childMode, values["asset-mode"])
        assertEquals(engine, values["asset-engine"]); assertEquals("$color-only", values["asset-branch"])
    }
}
