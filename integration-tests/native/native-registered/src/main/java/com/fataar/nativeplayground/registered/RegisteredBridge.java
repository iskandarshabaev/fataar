package com.fataar.nativeplayground.registered;
public final class RegisteredBridge {
 static { System.loadLibrary("fataar_registered"); }
 public static native int value(int input);
}
