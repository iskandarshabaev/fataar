plugins { id("com.android.library"); id("com.fataar.embeddable") }
android { namespace = "com.fataar.externaltest.embedded"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { dataBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 }
dependencies { api("com.fataar.external:binding-library:1") }
