package com.fataar.variants.sdk;
import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fataar.variants.flavored.FlavorMarker;
import com.fataar.variants.engine.EngineMarker;
import com.fataar.variants.base.BaseMarker;
public final class VariantFacade {
    private static String asset(Context context, String name) throws IOException {
        try (java.io.InputStream source = context.getAssets().open("variant/" + name + ".txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int n;
            while ((n = source.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public static Map<String,String> inspect(Context context) throws IOException {
        Map<String,String> values = new LinkedHashMap<>();
        values.put("sdk-color", BuildConfig.FLAVOR); values.put("sdk-mode", BuildConfig.BUILD_TYPE);
        values.put("color", FlavorMarker.text()); values.put("engine", EngineMarker.text());
        values.put("base", BaseMarker.text()); values.put("branch", FlavorOnly.text());
        values.put("extra", SdkExtras.text()); values.put("build", SdkBuild.text());
        values.put("host-resource", context.getString(R.string.variant_sdk_marker));
        values.put("child-resource", context.getString(com.fataar.variants.flavored.R.string.variant_color));
        for (String name : new String[]{"color", "mode", "engine", "branch"}) values.put("asset-"+name, asset(context,name));
        return values;
    }
}
