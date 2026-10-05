plugins { id("com.android.library"); id("com.fataar"); `maven-publish` }
android { namespace = "com.fataar.externaltest.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { dataBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 }
dependencies {
    "embed"(project(":embedded"))
    if (providers.gradleProperty("test.hideExternalApi").isPresent) implementation("com.fataar.external:binding-library:1")
    else api("com.fataar.external:binding-library:1")
    api("androidx.collection:collection:1.0.0")
}
publishing { repositories.maven { name = "Sdk"; url = uri(rootProject.layout.buildDirectory.dir("sdk-repository")) } }
components.configureEach {
    if (name == "fatRelease") {
        val component = this
        publishing.publications.register<MavenPublication>("sdk") { from(component) }
    }
}
