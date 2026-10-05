package com.fataar.fixtures.manifestcomponents
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "activity-ready"
        setContentView(android.widget.TextView(this).apply { text = "activity-ready" })
    }
}
class FixtureService : Service() {
    private val binder = android.os.Binder().apply { attachInterface(null, "fixture-service-ready") }
    override fun onBind(intent: Intent?): IBinder = binder
}
class FixtureReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { resultData = "receiver-ready" } }
open class FixtureProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        Bundle().apply { putString("marker", "provider-ready") }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Used by the real duplicate-authority negative build. */
class SecondaryProvider : FixtureProvider()
