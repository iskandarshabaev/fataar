# Deliberately referenced by a class name string: the published AAR must retain this rule.
-keep class com.fataar.fixtures.consumerrules.HiddenGreeting {
    public <init>();
    public java.lang.String message();
}
