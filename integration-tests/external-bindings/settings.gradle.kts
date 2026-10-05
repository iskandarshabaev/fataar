pluginManagement { includeBuild("../../plugin-build"); repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("build/external-repository"); content { includeGroup("com.fataar.external") } }
        google(); mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
rootProject.name = "ExternalBindingPlayground"
include(":external", ":embedded", ":sdk")
