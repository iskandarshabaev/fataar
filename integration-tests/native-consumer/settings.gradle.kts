pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("../native/build/repository")
            content { includeGroup("com.fataar.nativeplayground") }
            metadataSources {
                if (providers.gradleProperty("pomOnly").orNull == "true") { mavenPom(); ignoreGradleMetadataRedirection() }
                else { gradleMetadata(); mavenPom() }
            }
        }
        google(); mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
rootProject.name = "PublishedNativeConsumer"; include(":app")
