plugins { id("com.android.library"); id("com.fataar.embeddable") }
android {
    namespace = "com.fataar.variants.engine"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    flavorDimensions += "engine"
    productFlavors { create("modern") { dimension = "engine" }; create("classic") { dimension = "engine" } }
}
