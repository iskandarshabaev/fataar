# Preserve only the facade invoked from the separate test APK.
# Reflection targets in fixture modules still rely on their own consumer rules.
-keep class com.fataar.sdk.SdkProbe { public *; }
-keep class com.fataar.sdk.SdkPanel { public *; }
# AGP excludes dependencies shared with the tested APK from the test APK. Retain the
# shared runner/Kotlin runtime in the host for instrumentation; fixture code stays optimized.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

-keep class com.fataar.sdk.SdkGeneratedProbe { public *; }
-keep class com.fataar.consumerprobe.ConsumerGeneratedProbe { public *; }
