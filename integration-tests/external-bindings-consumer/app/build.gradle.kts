plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.fataar.externaltest.consumer"; compileSdk = 36
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "com.fataar.externaltest.consumer"; minSdk = 24; targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { dataBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "instrumentation-access.pro")
            testProguardFiles("instrumentation-r8.pro")
        }
    }
}
dependencies {
    implementation("com.fataar.externaltest:sdk:1")
    androidTestImplementation(libs.androidx.junit); androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.errorprone.annotations)
}
