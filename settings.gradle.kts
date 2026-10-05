pluginManagement {
    includeBuild("plugin-build")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
rootProject.name = "FatAarPlugin"
include(":app", ":sdk")
include(
    ":fixtures:jvm-core", ":fixtures:android-core",
    ":fixtures:resources-values", ":fixtures:resources-files",
    ":fixtures:manifest-components", ":fixtures:assets", ":fixtures:java-resources",
    ":fixtures:dependency-leaf", ":fixtures:dependency-left", ":fixtures:dependency-right",
    ":fixtures:dependency-graph", ":fixtures:consumer-rules",
    ":fixtures:aidl", ":fixtures:view-binding",
    ":fixtures:data-binding-left", ":fixtures:data-binding-right",
)
