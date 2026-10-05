package com.fataar.fixtures.assets
import android.content.Context
object AssetProbe {
    fun read(context: Context) = context.assets.open("fixture/config.json").bufferedReader().use { it.readText().trim() }
}
