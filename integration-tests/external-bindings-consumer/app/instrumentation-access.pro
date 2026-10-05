-keep class com.fataar.externaltest.consumer.ConsumerProbe { public static *; }
# AGP excludes dependencies shared with the tested APK from the test APK. Retain
# the shared runner/Kotlin runtime in this instrumentation host; SDK code stays optimized.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
