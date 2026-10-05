package com.fataar.sdk
import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.fataar.fixtures.resourcesfiles.R as FilesR

/** ConstraintLayout is deliberately part of the SDK's public ABI. */
class SdkPanel(context: Context) : ConstraintLayout(context) {
    init {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        LayoutInflater.from(context).inflate(FilesR.layout.fixture_panel, content, true)
        content.addView(TextView(context).apply {
            setPadding(24, 24, 24, 24)
            text = SdkProbe.inspect(context).entries.joinToString("\n\n") { (key, value) -> "$key: $value" }
        })
        addView(ScrollView(context).apply { addView(content) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
