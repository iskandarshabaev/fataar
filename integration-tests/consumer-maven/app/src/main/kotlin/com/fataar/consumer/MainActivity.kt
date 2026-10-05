package com.fataar.consumer
import android.app.Activity
import android.os.Bundle
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.core.Metrics
import com.fataar.sdk.SdkPanel
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel: ConstraintLayout = SdkPanel(this)
        panel.fillMetrics(Metrics())
        setContentView(panel)
    }
}
