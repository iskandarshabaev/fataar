plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.fataar.consumer"
    compileSdk = 36
    buildFeatures { aidl = true; dataBinding = true; viewBinding = true }
    sourceSets.getByName("main") {
        java.srcDir("../../../app/src/consumerGenerated/java"); res.srcDir("../../../app/src/consumerGenerated/res"); aidl.srcDir("../../../app/src/consumerGenerated/aidl")
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    sourceSets.getByName("androidTest").kotlin.srcDir("../../../app/src/androidTest/java")
    defaultConfig {
        applicationId = "com.fataar.consumer"
        manifestPlaceholders["fataarChannel"] = "maven-channel"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (testBuildType == "release") {
                signingConfig = signingConfigs.getByName("debug")
                proguardFiles("../../../app/instrumentation-access.pro")
                testProguardFiles("../../../app/instrumentation-r8.pro")
            }
        }
    }
}
dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.errorprone.annotations)
    // Intentionally no project dependencies or direct ConstraintLayout dependency.
    implementation("com.fataar.playground:sdk:0.2.0-merged")
}
