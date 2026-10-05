package com.fataar.nativeplayground.sdk;
import com.fataar.nativeplayground.bridge.NativeBridge;
import com.fataar.nativeplayground.registered.RegisteredBridge;
public final class NativeSdk {
    static { System.loadLibrary("fataar_host"); }
    private static native int hostValue(int input);
    public static String inspect(int input) {
        return NativeBridge.value(input) + ":" + RegisteredBridge.value(input) + ":" + hostValue(input);
    }
}
