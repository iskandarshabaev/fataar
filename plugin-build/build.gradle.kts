plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}
group = "com.fataar"
version = "0.1.0"
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.google.code.gson:gson:2.11.0") // AGP metadata JSON; version aligned with AGP.
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-commons:9.9")
    implementation("com.android.tools.build:gradle:" + libs.versions.agp.get())
    implementation("com.android.tools:common:32.4.0") // ILogger API used by AGP 9.4's manifest merger.
    testImplementation(gradleTestKit())
    testImplementation(libs.junit)
}
gradlePlugin {
    plugins {
        create("embeddable") {
            id = "com.fataar.embeddable"
            implementationClass = "com.fataar.gradle.EmbeddablePlugin"
        }
        create("fatAar") {
            id = "com.fataar"
            implementationClass = "com.fataar.gradle.FatAarPlugin"
        }
        create("androidFixture") {
            id = "com.fataar.playground.android-library"
            implementationClass = "com.fataar.gradle.AndroidLibraryConventionPlugin"
        }
        create("jvmFixture") {
            id = "com.fataar.playground.java-library"
            implementationClass = "com.fataar.gradle.JavaLibraryConventionPlugin"
        }
    }
}
tasks.test {
    maxParallelForks = 1
    maxHeapSize = "1g"
}
