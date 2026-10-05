package com.fataar.nativeplayground.consumer;
import com.fataar.nativeplayground.sdk.NativeSdk;
public final class ConsumerProbe {
    static { System.loadLibrary("fataar_consumer"); }
    private static native int nativeValue(int input);
    public static String sdkValue(int input) { return NativeSdk.inspect(input); }
    public static int prefabValue(int input) { return nativeValue(input); }
}
