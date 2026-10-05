# Test APK only: Error Prone's CLASS-retained @IncompatibleModifiers has a deprecated
# member of this javac enum type. The enum is not an Android runtime dependency.
# SDK and demo app R8 rules remain strict.
-dontwarn javax.lang.model.element.Modifier
# AndroidJUnitRunner discovers this class and its @Test methods reflectively in the test APK.
-keep class com.fataar.plugin.SdkSmokeTest { *; }
-keepattributes *Annotation*
