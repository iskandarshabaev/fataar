-keep class com.fataar.nativeplayground.consumer.ConsumerProbe { public static *; }
-keepclasseswithmembernames,includedescriptorclasses class com.fataar.nativeplayground.consumer.ConsumerProbe { native <methods>; }
# Shared instrumentation libraries are excluded by AGP from the test APK.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
