plugins { id("com.fataar.playground.android-library") }
dependencies {
    implementation(project(":fixtures:dependency-left"))
    implementation(project(":fixtures:dependency-right"))
}
