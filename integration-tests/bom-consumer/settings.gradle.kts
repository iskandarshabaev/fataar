pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories {
 maven { url = uri("../bom/build/sdk-repository"); content { includeGroup("com.fataar.bomfixture") }
  metadataSources { if (providers.gradleProperty("pomOnly").isPresent) { mavenPom(); ignoreGradleMetadataRedirection() } else { gradleMetadata(); mavenPom() } }
 }
 maven { url = uri("../bom/build/family-repository"); content { includeGroup("com.fataar.bomfixture") }
  metadataSources { if (providers.gradleProperty("pomOnly").isPresent) { mavenPom(); ignoreGradleMetadataRedirection() } else { gradleMetadata(); mavenPom() } }
 }
 google(); mavenCentral()
 }
 versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
rootProject.name = "PublishedBomConsumer"; include(":app")
