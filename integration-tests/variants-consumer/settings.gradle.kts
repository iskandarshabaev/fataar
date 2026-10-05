pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("../variants/build/repository")
            content { includeGroup("com.fataar.variants") }
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
rootProject.name = "PublishedVariantConsumer"; include(":app")
