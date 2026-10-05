plugins { id("com.fataar.playground.android-library") }
android {
    buildFeatures { buildConfig = true }
    defaultConfig { buildConfigField("String", "FIXTURE_MARKER", "\"android-core\"") }
}
dependencies { implementation(project(":fixtures:jvm-core")) }
