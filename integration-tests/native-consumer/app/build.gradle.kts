plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.fataar.nativeplayground.consumer"; compileSdk = 36; ndkVersion = "25.1.8937393"
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "com.fataar.nativeplayground.maven"; minSdk = 24; targetSdk = 36
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("../../native/app/src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release {
        isMinifyEnabled = true; isShrinkResources = true
        signingConfig = signingConfigs.getByName("debug")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../../native/app/instrumentation-access.pro")
        testProguardFiles("../../native/app/instrumentation-r8.pro")
    } }
}
android {
    sourceSets.getByName("main") { java.srcDir("../../native/app/src/main/java"); res.srcDir("../../native/app/src/main/res") }
    sourceSets.getByName("androidTest").java.srcDir("../../native/app/src/androidTest/java")
}
dependencies {
    implementation("com.fataar.nativeplayground:sdk:1")
    androidTestImplementation(libs.androidx.junit); androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.errorprone.annotations)
}
