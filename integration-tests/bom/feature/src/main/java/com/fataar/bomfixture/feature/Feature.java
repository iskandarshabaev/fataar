package com.fataar.bomfixture.feature;
import com.fataar.bomfixture.ApiModel;
import com.fataar.bomfixture.Engine;
public final class Feature {
 public static ApiModel model() { return new ApiModel(); }
 public static String versions() { return ApiModel.version() + ":" + Engine.version(); }
}
