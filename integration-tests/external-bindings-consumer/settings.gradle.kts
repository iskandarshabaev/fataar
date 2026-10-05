pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("../external-bindings/build/sdk-repository")
            content { includeGroup("com.fataar.externaltest") }
            metadataSources {
                if (providers.gradleProperty("pomOnly").orNull == "true") { mavenPom(); ignoreGradleMetadataRedirection() }
                else { gradleMetadata(); mavenPom() }
            }
        }
        maven {
            url = uri("../external-bindings/build/external-repository")
            content { includeGroup("com.fataar.external") }
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
rootProject.name = "PublishedExternalBindingConsumer"; include(":app")
