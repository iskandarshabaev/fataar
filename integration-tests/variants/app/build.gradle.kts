plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.fataar.variants.app"; compileSdk = 36
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "com.fataar.variants.project"; minSdk = 24; targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PUBLISHED_SDK", "false")
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "channel"
    productFlavors { create("red") { dimension = "channel"; applicationIdSuffix = ".red" }; create("blue") { dimension = "channel"; applicationIdSuffix = ".blue" } }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "instrumentation-access.pro")
            testProguardFiles("instrumentation-r8.pro")
        }
        create("staging") { initWith(getByName("debug")) }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":sdk")) { capabilities { requireCapability("com.fataar.variants:sdk-fat") } }
    androidTestImplementation(libs.androidx.junit); androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.errorprone.annotations)
}
