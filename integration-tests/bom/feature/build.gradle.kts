plugins { id("com.android.library"); id("com.fataar.embeddable") }
android { namespace = "com.fataar.bomfixture.feature"; compileSdk = 36; defaultConfig { minSdk = 24 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
dependencies {
 implementation(platform("com.fataar.bomfixture:nested-bom:2"))
 implementation("com.fataar.bomfixture:api-lib")
 implementation("com.fataar.bomfixture:engine-lib")
}
