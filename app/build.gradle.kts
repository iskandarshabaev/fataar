plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.fataar.plugin"
    compileSdk = 36
    buildFeatures { aidl = true; dataBinding = true; viewBinding = true }
    sourceSets.getByName("main") {
        java.srcDir("src/consumerGenerated/java"); res.srcDir("src/consumerGenerated/res"); aidl.srcDir("src/consumerGenerated/aidl")
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "com.fataar.plugin"
        manifestPlaceholders["fataarChannel"] = "project-channel"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-baseline"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (testBuildType == "release") {
                // Test-only entry points for a separate instrumentation APK; fixture code still goes through R8.
                signingConfig = signingConfigs.getByName("debug")
                proguardFiles("instrumentation-access.pro")
                testProguardFiles("instrumentation-r8.pro")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":sdk")) {
        capabilities { requireCapability("com.fataar.playground:sdk-fat") }
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    // AndroidX Test refers to these annotations; R8 checks the test APK too.
    androidTestImplementation(libs.errorprone.annotations)
}
