plugins {
    id("com.android.library")
    id("com.fataar")
    `maven-publish`
    signing
}
group = "com.fataar.playground"
version = "0.2.0-merged"
android {
    namespace = "com.fataar.sdk"
    compileSdk = 36
    buildFeatures { dataBinding = true }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
fatAar { autoEmbedProjectPrefixes.add(":fixtures:"); withSourcesJar() }
dependencies {
    // Embedded modules are physically packaged; external scopes remain Maven dependencies.
    "embed"(project(":fixtures:android-core"))
    "embed"(project(":fixtures:jvm-core"))
    "embed"(project(":fixtures:resources-files"))
    "embed"(project(":fixtures:resources-values"))
    "embed"(project(":fixtures:manifest-components"))
    "embed"(project(":fixtures:assets"))
    "embed"(project(":fixtures:dependency-graph"))
    "embed"(project(":fixtures:consumer-rules"))
    "embed"(project(":fixtures:java-resources"))
    "embed"(project(":fixtures:aidl"))
    "embed"(project(":fixtures:view-binding"))
    "embed"(project(":fixtures:data-binding-left"))
    "embed"(project(":fixtures:data-binding-right"))
    api(libs.androidx.constraintlayout)
    // Inherited public/protected ConstraintLayout methods expose core widget and Metrics types.
    api(libs.androidx.constraintlayout.core)
    // ViewDataBinding exposes LongSparseArray in protected methods.
    api("androidx.collection:collection:1.0.0")
    implementation("org.jetbrains:annotations:26.0.2")
}
publishing {
    repositories.maven {
        name = "Merged"
        url = uri(rootProject.layout.buildDirectory.dir("merged-repository"))
    }
}
val publication = publishing.publications.register<MavenPublication>("merged")
components.configureEach {
    if (name == "fatRelease") {
        val fatComponent = this
        publication.configure { from(fatComponent) }
    }
}
// Verification supplies an ephemeral test key; real publishing can supply its own in-memory key.
val signingKeyPath = providers.gradleProperty("fatAar.signingKeyFile")
if (signingKeyPath.isPresent) {
    val key = providers.fileContents(layout.projectDirectory.file(signingKeyPath.get())).asText
    signing { useInMemoryPgpKeys(key.get(), providers.gradleProperty("fatAar.signingPassword").orElse("").get()); sign(publication.get()) }
}
