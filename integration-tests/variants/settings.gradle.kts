pluginManagement { includeBuild("../../plugin-build"); repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
rootProject.name = "VariantPlayground"
include(":sdk", ":flavored", ":engine", ":base", ":red-only", ":blue-only", ":red-leaf", ":release-only", ":staging-only", ":app")
