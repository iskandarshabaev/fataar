plugins { id("com.android.library"); id("com.fataar.embeddable") }
android {
    namespace = "com.fataar.variants.releaseonly"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

}
