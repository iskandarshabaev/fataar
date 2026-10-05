plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
tasks.register("verifyPlayground") {
    group = "verification"
    description = "Builds the merged SDK and demo, inspects the AAR and tests the plugin."
    dependsOn(
        ":sdk:inspectReleaseAar", ":app:assembleDebug", ":app:assembleRelease",
        ":app:lintDebug", ":fixtures:jvm-core:test",
        gradle.includedBuild("plugin-build").task(":check"),
    )
}
