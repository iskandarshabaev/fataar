plugins { alias(libs.plugins.android.application) }
val scope = providers.gradleProperty("test.bomScope").orElse("api").get()
android { namespace = "com.fataar.bomfixture.consumer"; compileSdk = 36
 defaultConfig { applicationId = "com.fataar.bomfixture.consumer"; minSdk = 24; targetSdk = 36 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 implementation("com.fataar.bomfixture:sdk-" + scope + ":2")
 if (providers.gradleProperty("test.upgrade").isPresent) implementation("com.fataar.bomfixture:engine-lib:3")
}

abstract class RuntimeReport : DefaultTask() {
 @get:Input abstract val entries: org.gradle.api.provider.ListProperty<String>
 @get:OutputFile abstract val report: org.gradle.api.file.RegularFileProperty
 @TaskAction fun write() { report.get().asFile.apply { parentFile.mkdirs(); writeText(entries.get().joinToString("\n", postfix = "\n")) } }
}
val androidComponents = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
 val artifacts = variant.runtimeConfiguration.incoming.artifacts
 tasks.register<RuntimeReport>("reportRuntime") {
  dependsOn(artifacts.artifactFiles)
  entries.set(artifacts.resolvedArtifacts.map { values -> values.map {
   val id = it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
   id.group + ":" + id.module + ":" + id.version + "\t" + it.file.absolutePath
  }.sorted() })
  report.set(layout.buildDirectory.file("reports/runtime.txt"))
 }
}
