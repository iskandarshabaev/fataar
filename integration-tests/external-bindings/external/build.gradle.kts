plugins { id("com.android.library"); `maven-publish` }
group = "com.fataar.external"
android {
    namespace = "com.fataar.external.binding"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { dataBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") }
}
dependencies { api("androidx.collection:collection:1.0.0") }
publishing { repositories.maven { name = "External"; url = uri(rootProject.layout.buildDirectory.dir("external-repository")) } }
components.configureEach {
    if (name == "release") {
        val component = this
        publishing.publications.register<MavenPublication>("external") { artifactId = "binding-library"; from(component) }
    }
}
