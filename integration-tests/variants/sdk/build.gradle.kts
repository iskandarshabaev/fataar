plugins { id("com.android.library"); id("com.fataar"); `maven-publish` }
android {
    namespace = "com.fataar.variants.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        if (!providers.gradleProperty("test.noDimension").isPresent) missingDimensionStrategy("engine", "modern")
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "channel"
    productFlavors {
        create("red") { dimension = "channel" }
        create("blue") { dimension = "channel"; missingDimensionStrategy("engine", "classic") }
    }
    buildTypes {
        create("staging") {
            initWith(getByName("debug"))
            if (!providers.gradleProperty("test.noBuildFallback").isPresent) matchingFallbacks += "debug"
        }
    }
    // Create this combined source set before using redReleaseEmbed in dependencies.
    sourceSets.maybeCreate("redRelease")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
fatAar {
    autoEmbedProjectPrefixes.add(":red-")
    if (providers.gradleProperty("test.sources").isPresent) withSourcesJar()
}
dependencies {
    "embed"(project(":flavored")); "embed"(project(":engine")); "embed"(project(":base"))
    "redEmbed"(project(":red-only")); "blueEmbed"(project(":blue-only"))
    if (!providers.gradleProperty("test.omitRedLeaf").isPresent) "redEmbed"(project(":red-leaf"))
    "redReleaseEmbed"(project(":release-only")); "stagingEmbed"(project(":staging-only"))
    "blueApi"("org.jetbrains:annotations:26.0.2")
    "redImplementation"("org.jetbrains:annotations:26.0.2")
}
publishing { repositories.maven { name = "Variants"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
components.configureEach {
    if (name == "fatRedRelease" || name == "fatBlueRelease") {
        val component = this
        val color = if (name == "fatRedRelease") "red" else "blue"
        publishing.publications.register<MavenPublication>(color) { artifactId = "sdk-$color"; from(component) }
    }
}
