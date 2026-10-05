pluginManagement { includeBuild("../../plugin-build"); repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { maven { url = uri("build/family-repository"); content { includeGroup("com.fataar.bomfixture") } }; google(); mavenCentral() }
 versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
rootProject.name = "BomPlayground"
include(":sdk", ":feature", ":family-bom", ":nested-bom")
for (kind in listOf("api-lib", "engine-lib", "embedded-bundle")) for (v in 1..2) include(":$kind$v")
include(":engine-lib3")
