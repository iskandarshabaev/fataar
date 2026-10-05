plugins { id("com.android.library"); id("com.fataar.embeddable") }
android {
    namespace = "com.fataar.variants.flavored"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    flavorDimensions += "channel"
    productFlavors { create("red") { dimension = "channel" }; create("blue") { dimension = "channel" } }
}
dependencies { "redImplementation"(project(":red-leaf")) }
