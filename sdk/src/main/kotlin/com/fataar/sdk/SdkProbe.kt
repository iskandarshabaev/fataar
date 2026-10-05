package com.fataar.sdk
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.fataar.fixtures.manifestcomponents.FixtureActivity
import com.fataar.fixtures.manifestcomponents.FixtureService
import com.fataar.fixtures.manifestcomponents.FixtureReceiver
import com.fataar.fixtures.manifestcomponents.FixtureProvider
import android.net.Uri
import com.fataar.fixtures.androidcore.AndroidCore
import com.fataar.fixtures.androidcore.fixtureTypeName
import com.fataar.fixtures.assets.AssetProbe
import com.fataar.fixtures.consumerrules.ReflectionProbe
import com.fataar.fixtures.dependencygraph.GraphProbe
import com.fataar.fixtures.jvmcore.MessageProvider
import com.fataar.fixtures.resourcesfiles.R as FilesR
import com.fataar.fixtures.resourcesvalues.R as ValuesR
import java.util.Properties
import java.util.ServiceLoader

object SdkProbe {
    @Suppress("DiscouragedApi")
    fun inspect(context: Context): Map<String, String> {
        val loader = SdkProbe::class.java.classLoader!!
        val properties = Properties()
        requireNotNull(loader.getResourceAsStream("com/fataar/fixture.properties")).use { properties.load(it) }
        val dynamicId = context.resources.getIdentifier("fixture_dynamic_name", "string", context.packageName)
        check(dynamicId != 0) { "String resource fixture_dynamic_name is missing" }
        val provider = context.contentResolver.call(Uri.parse("content://" + context.packageName + ".fixture"), "probe", null, null)
        return linkedMapOf(
            "sdk-resource" to context.getString(R.string.sdk_stage),
            "core" to AndroidCore.marker(),
            "kotlin-metadata" to fixtureTypeName<String>(),
            "graph" to GraphProbe.marker(),
            "asset" to AssetProbe.read(context),
            "java-resource" to properties.getProperty("marker"),
            "spi" to ServiceLoader.load(MessageProvider::class.java, loader).map { it.message() }.sorted().joinToString(","),
            "reflection" to ReflectionProbe.marker(),
            "provider" to requireNotNull(provider?.getString("marker")),
            "resource-by-name" to context.getString(dynamicId),
            "resource-value" to context.resources.getInteger(ValuesR.integer.fixture_answer).toString(),
            "raw" to context.resources.openRawResource(FilesR.raw.fixture_payload).bufferedReader().use { it.readText().trim() },
        ) + inspectResources(context)
    }

    /** Exercises resource APIs through the SDK, including cross-module R references. */
    fun inspectResources(context: Context): Map<String, String> {
        val resources = context.resources
        val binary = resources.openRawResource(FilesR.raw.fixture_binary).use { it.readBytes() }
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeResource(resources, FilesR.drawable.fixture_pixels))
        val pixels = try { "${bitmap.width}x${bitmap.height}:${bitmap.getPixel(0, 0)}:${bitmap.getPixel(1, 1)}" }
            finally { bitmap.recycle() }
        val xmlVersion = resources.getXml(FilesR.xml.fixture_config).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) {
                check(xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
            }
            xml.getAttributeValue(null, "version")
        }
        return linkedMapOf(
            "title" to context.getString(ValuesR.string.fixture_title),
            "plural-one" to resources.getQuantityString(ValuesR.plurals.fixture_items, 1, 1),
            "plural-few" to resources.getQuantityString(ValuesR.plurals.fixture_items, 2, 2),
            "plural-many" to resources.getQuantityString(ValuesR.plurals.fixture_items, 5, 5),
            "labels" to resources.getStringArray(ValuesR.array.fixture_labels).joinToString(","),
            "enabled" to resources.getBoolean(ValuesR.bool.fixture_enabled).toString(),
            "accent" to resources.getColor(ValuesR.color.fixture_accent, context.theme).toString(),
            "selector-enabled" to resources.getColorStateList(FilesR.color.fixture_tint, context.theme)
                .getColorForState(intArrayOf(android.R.attr.state_enabled), 0).toString(),
            "selector-disabled" to resources.getColorStateList(FilesR.color.fixture_tint, context.theme)
                .getColorForState(intArrayOf(-android.R.attr.state_enabled), 0).toString(),
            "vector-width" to requireNotNull(context.getDrawable(FilesR.drawable.fixture_vector)).intrinsicWidth.toString(),
            "shape" to (context.getDrawable(FilesR.drawable.fixture_shape) is android.graphics.drawable.GradientDrawable).toString(),
            "animation-duration" to android.view.animation.AnimationUtils.loadAnimation(context, FilesR.anim.fixture_fade).duration.toString(),
            "animator-duration" to android.animation.AnimatorInflater.loadAnimator(context, FilesR.animator.fixture_alpha).duration.toString(),
            "xml-version" to xmlVersion,
            "binary" to binary.joinToString(",") { (it.toInt() and 255).toString() },
            "pixels" to pixels,
        )
    }

    fun fixtureActivityIntent(context: Context): Intent = Intent(context, FixtureActivity::class.java)
    fun fixtureServiceIntent(context: Context): Intent = Intent(context, FixtureService::class.java)
    fun fixtureReceiverIntent(context: Context): Intent = Intent(context, FixtureReceiver::class.java)

    @Suppress("DEPRECATION")
    fun inspectManifest(context: Context): Map<String, String> {
        val manager = context.packageManager
        val application = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val packageInfo = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val provider = manager.getProviderInfo(ComponentName(context, FixtureProvider::class.java), 0)
        return linkedMapOf(
            "activity-exported" to manager.getActivityInfo(ComponentName(context, FixtureActivity::class.java), 0).exported.toString(),
            "service-exported" to manager.getServiceInfo(ComponentName(context, FixtureService::class.java), 0).exported.toString(),
            "receiver-exported" to manager.getReceiverInfo(ComponentName(context, FixtureReceiver::class.java), 0).exported.toString(),
            "provider-exported" to provider.exported.toString(),
            "provider-authority" to requireNotNull(provider.authority),
            "manifest-marker" to requireNotNull(application.metaData.getString("com.fataar.fixture")),
            "override" to requireNotNull(application.metaData.getString("com.fataar.fixture.override")),
            "removed" to application.metaData.containsKey("com.fataar.fixture.removed").toString(),
            "channel" to requireNotNull(application.metaData.getString("com.fataar.fixture.channel")),
            "permission-present" to packageInfo.requestedPermissions.orEmpty().contains("android.permission.ACCESS_NETWORK_STATE").toString(),
            "permission-removed" to packageInfo.requestedPermissions.orEmpty().contains("android.permission.VIBRATE").toString(),
        )
    }
}
