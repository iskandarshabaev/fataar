plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.fataar.variants.app"; compileSdk = 36
    sourceSets.getByName("androidTest").kotlin.srcDir("../../variants/app/src/androidTest/kotlin")
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "com.fataar.variants.maven"; minSdk = 24; targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PUBLISHED_SDK", "true")
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "channel"
    productFlavors { create("red") { dimension = "channel"; applicationIdSuffix = ".red" }; create("blue") { dimension = "channel"; applicationIdSuffix = ".blue" } }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../../variants/app/instrumentation-access.pro")
            testProguardFiles("../../variants/app/instrumentation-r8.pro")
        }
        create("staging") { initWith(getByName("debug")) }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    "redImplementation"("com.fataar.variants:sdk-red:1")
    "blueImplementation"("com.fataar.variants:sdk-blue:1")
    androidTestImplementation(libs.androidx.junit); androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.errorprone.annotations)
}
