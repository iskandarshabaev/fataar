plugins { id("com.android.library"); id("com.fataar.embeddable") }
android {
    namespace = "com.fataar.nativeplayground.registered"
    compileSdk = 36; ndkVersion = "25.1.8937393"
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { prefab = true; prefabPublishing = true }
    prefab { create("fataar_registered") { headers = "src/main/cpp/include" } }
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}
