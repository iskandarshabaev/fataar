plugins { id("com.android.library"); id("com.fataar"); `maven-publish`; signing }
android {
    namespace = "com.fataar.nativeplayground.sdk"
    compileSdk = 36; ndkVersion = "25.1.8937393"
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { prefab = true; prefabPublishing = true }
    prefab { create("fataar_host") { headers = "src/main/cpp/include" } }
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}
dependencies { "embed"(project(":native-base")); "embed"(project(":native-bridge")); "embed"(project(":native-registered")) }
publishing { repositories.maven { name = "Native"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
components.configureEach { if (name == "fatRelease") {
    val component = this
    publishing.publications.register<MavenPublication>("sdk") { from(component) }
} }

val signingKeyPath = providers.gradleProperty("fatAar.signingKeyFile")
if (signingKeyPath.isPresent) {
    val key = providers.fileContents(layout.projectDirectory.file(signingKeyPath.get())).asText
    signing { useInMemoryPgpKeys(key.get(), providers.gradleProperty("fatAar.signingPassword").orElse("").get()) }
    publishing.publications.withType<MavenPublication>().configureEach { signing.sign(this) }
}

fatAar { nativeAbis.addAll("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
