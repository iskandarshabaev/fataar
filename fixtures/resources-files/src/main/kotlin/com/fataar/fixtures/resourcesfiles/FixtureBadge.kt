package com.fataar.fixtures.resourcesfiles
import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import com.fataar.fixtures.resourcesvalues.R as ValuesR
class FixtureBadge @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    init {
        val attributes = context.obtainStyledAttributes(attrs, ValuesR.styleable.FixtureBadge)
        try {
            text = attributes.getString(ValuesR.styleable.FixtureBadge_fixtureLabel) ?: "badge"
            if (attributes.getBoolean(ValuesR.styleable.FixtureBadge_fixtureEmphasis, false)) setTypeface(typeface, 1)
        } finally { attributes.recycle() }
    }
}
