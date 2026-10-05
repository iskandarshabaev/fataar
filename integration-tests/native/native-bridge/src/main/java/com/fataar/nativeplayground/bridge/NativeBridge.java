package com.fataar.nativeplayground.bridge;
public final class NativeBridge {
 static { System.loadLibrary("fataar_bridge"); }
 public static native int value(int input);
}
