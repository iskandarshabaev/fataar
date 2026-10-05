package com.fataar.variants.app;
public final class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state); android.widget.TextView view = new android.widget.TextView(this);
        try { view.setText(com.fataar.variants.sdk.VariantFacade.inspect(this).toString()); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        setContentView(view);
    }
}
