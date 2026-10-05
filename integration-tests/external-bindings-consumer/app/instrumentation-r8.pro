# AndroidJUnitRunner discovers this test class and its annotated methods reflectively.
-keep class com.fataar.externaltest.consumer.ExternalBindingTest { *; }
-keepattributes *Annotation*
# CLASS-retained Error Prone annotation metadata references this javac-only enum.
-dontwarn javax.lang.model.element.Modifier
