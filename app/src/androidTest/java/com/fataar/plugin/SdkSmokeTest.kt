package com.fataar.plugin
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fataar.sdk.SdkPanel
import com.fataar.sdk.SdkProbe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SdkSmokeTest {
    @Test fun allFixturePayloadsAreReachableThroughSdk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = SdkProbe.inspect(context)
        assertEquals("merged-sdk", values["sdk-resource"])
        assertEquals("android-core:java", values["core"])
        assertEquals("String", values["kotlin-metadata"])
        assertEquals("left->leaf|right->leaf", values["graph"])
        assertEquals("{\"kind\":\"asset\",\"version\":1}", values["asset"])
        assertEquals("java-resource-ready", values["java-resource"])
        assertEquals("spi-ready", values["spi"])
        assertEquals("reflection-ready", values["reflection"])
        assertEquals("provider-ready", values["provider"])
        assertEquals("resolved-by-name", values["resource-by-name"])
        assertEquals("42", values["resource-value"])
        assertEquals("{\"kind\":\"raw\",\"version\":1}", values["raw"])
    }
    @Test fun sdkViewInflatesItsCustomXmlView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val portrait = configuredContext("en", false, false)
            val panel: androidx.constraintlayout.widget.ConstraintLayout = SdkPanel(portrait)
            assertEquals(1, panel.childCount)
            val context = portrait
            val badgeId = context.resources.getIdentifier("fixture_badge", "id", context.packageName)
            assertNotEquals(0, badgeId)
            val badge = panel.findViewById<android.widget.TextView>(badgeId)
            val titleId = context.resources.getIdentifier("fixture_title", "string", context.packageName)
            assertEquals(context.getString(titleId), badge.text.toString())
            assertTrue("styleable boolean index must survive merging", badge.typeface.isBold)
        }
    }

    @Test fun generatedBindingsAndObservableUpdatesWorkThroughSdk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val values = com.fataar.sdk.SdkGeneratedProbe.inspectBindings(instrumentation.targetContext)
            assertEquals("view-binding-ready", values["view"])
            assertEquals("view-updated", values["view-updated"])
            assertEquals("left-initial", values["left"])
            assertEquals("left-observed", values["left-updated"])
            assertEquals("right:42", values["right"])
            assertEquals("sdk-binding", values["host"])
            assertEquals("leftModel", values["left-br"])
            assertEquals("rightCount", values["right-br"])
        }
    }

    @Test fun consumerGeneratesBindingsIncludesAdaptersAndAidlImportsFromSdk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val values = com.fataar.consumerprobe.ConsumerGeneratedProbe.inspect(instrumentation.targetContext)
            assertEquals("consumer-left", values["left"])
            assertEquals("consumer-right:7", values["right"])
            assertEquals("adapter:consumer-right", values["adapter"])
            assertEquals("consumer-observed", values["left-updated"])
            assertEquals("import:parcel:9", values["aidl-import"])
        }
    }

    @Test fun aidlParcelableRoundTripUsesSeparateProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = java.util.concurrent.CountDownLatch(1)
        var binder: android.os.IBinder? = null
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                binder = service; connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        assertTrue(context.bindService(com.fataar.sdk.SdkGeneratedProbe.serviceIntent(context), connection, android.content.Context.BIND_AUTO_CREATE))
        try {
            assertTrue("AIDL service did not bind", connected.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val values = com.fataar.sdk.SdkGeneratedProbe.exchange(requireNotNull(binder))
            assertEquals("remote:request", values["message"])
            assertEquals("42", values["sequence"])
            assertNotEquals(android.os.Process.myPid().toString(), values["remote-pid"])
        } finally { context.unbindService(connection) }
    }

    private fun configuredContext(language: String, night: Boolean, landscape: Boolean): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = android.content.res.Configuration(base.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(language))
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                else android.content.res.Configuration.ORIENTATION_PORTRAIT
        }
        return base.createConfigurationContext(configuration)
    }

    @Test fun resourceQualifiersAndBinaryPayloadsSurviveMergeAndShrinking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (language in listOf("en", "ru")) for (night in listOf(false, true)) for (landscape in listOf(false, true)) {
                val context = configuredContext(language, night, landscape)
                val scenario = "$language night=$night landscape=$landscape"
                val values = SdkProbe.inspectResources(context)
                val title = if (language == "ru") "Полигон Fat AAR" else "Fat AAR playground"
                val accent = android.graphics.Color.parseColor(if (night) "#80CBC4" else "#00695C").toString()
                assertEquals(scenario, title, values["title"])
                assertEquals(scenario, if (language == "ru") "1 элемент" else "1 item", values["plural-one"])
                assertEquals(scenario, if (language == "ru") "2 элемента" else "2 items", values["plural-few"])
                assertEquals(scenario, if (language == "ru") "5 элементов" else "5 items", values["plural-many"])
                assertEquals(scenario, accent, values["accent"])
                assertEquals(scenario, accent, values["selector-enabled"])
                assertEquals(scenario, android.graphics.Color.parseColor("#808080").toString(), values["selector-disabled"])
                assertEquals(scenario, "alpha,beta", values["labels"])
                assertEquals(scenario, "true", values["enabled"])
                assertEquals(scenario, "true", values["shape"])
                assertEquals(scenario, "100", values["animation-duration"])
                assertEquals(scenario, "100", values["animator-duration"])
                assertEquals(scenario, "1", values["xml-version"])
                assertEquals(scenario, (0..255).joinToString(","), values["binary"])
                assertEquals(scenario, "2x2:${android.graphics.Color.RED}:${android.graphics.Color.WHITE}", values["pixels"])
                assertEquals(scenario, (24 * context.resources.displayMetrics.density + 0.5f).toInt().toString(), values["vector-width"])
                val panel = SdkPanel(context)
                val badgeId = context.resources.getIdentifier("fixture_badge", "id", context.packageName)
                val badge = requireNotNull(panel.findViewById<android.widget.TextView>(badgeId))
                assertEquals(scenario, title, badge.text.toString())
                assertEquals("$scenario: layout qualifier / styleable", !landscape, badge.typeface.isBold)
                if (!landscape) {
                    assertEquals("$scenario: style -> color qualifier", accent, badge.currentTextColor.toString())
                    assertEquals("$scenario: cross-module dimen", (12 * context.resources.displayMetrics.density + 0.5f).toInt(), badge.paddingLeft)
                }
            }
        }
    }

    @Test fun mergedManifestComponentsAndPlaceholdersWorkAtRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val values = SdkProbe.inspectManifest(context)
        for (kind in listOf("activity", "service", "receiver", "provider")) {
            assertEquals(kind, "false", values["$kind-exported"])
        }
        assertEquals(context.packageName + ".fixture", values["provider-authority"])
        assertEquals("manifest-present", values["manifest-marker"])
        assertEquals("sdk-value", values["override"])
        assertEquals("false", values["removed"])
        assertEquals(if (context.packageName == "com.fataar.plugin") "project-channel" else "maven-channel", values["channel"])
        assertEquals("true", values["permission-present"])
        assertEquals("false", values["permission-removed"])

        val activity = instrumentation.startActivitySync(SdkProbe.fixtureActivityIntent(context)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertEquals("activity-ready", activity.title.toString())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }

        val connected = java.util.concurrent.CountDownLatch(1)
        val binder = java.util.concurrent.atomic.AtomicReference<android.os.IBinder>()
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                binder.set(service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        assertTrue(context.bindService(SdkProbe.fixtureServiceIntent(context), connection, android.content.Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Merged service did not bind", connected.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("fixture-service-ready", requireNotNull(binder.get()).interfaceDescriptor)
        } finally {
            context.unbindService(connection)
        }

        val delivered = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<String>()
        context.sendOrderedBroadcast(SdkProbe.fixtureReceiverIntent(context), null,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    result.set(resultData)
                    delivered.countDown()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()), android.app.Activity.RESULT_OK, null, null)
        assertTrue("Merged receiver did not respond", delivered.await(15, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("receiver-ready", result.get())
    }
}
