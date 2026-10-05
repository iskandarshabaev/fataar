package com.fataar.plugin

import android.app.Activity
import android.os.Bundle
import androidx.constraintlayout.widget.ConstraintLayout
import com.fataar.sdk.SdkPanel

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No direct ConstraintLayout dependency: it must be exported by the SDK.
        val panel: ConstraintLayout = SdkPanel(this)
        setContentView(panel)
    }
}
