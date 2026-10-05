package com.fataar.bomfixture.sdk;
import com.fataar.bomfixture.ApiModel;
import com.fataar.bomfixture.feature.Feature;
import com.fataar.bomfixture.EmbeddedBundle;
public final class BomSdk {
 public static ApiModel model() { return Feature.model(); }
 public static String versions() { return Feature.versions() + ":" + EmbeddedBundle.version(); }
}
