package com.fataar.fixtures.databindingleft;
public final class FixtureBindingAdapters {
    @androidx.databinding.BindingAdapter("fixtureBindingSuffix")
    public static void suffix(android.widget.TextView view, String value) { view.setText("adapter:" + value); }
}
