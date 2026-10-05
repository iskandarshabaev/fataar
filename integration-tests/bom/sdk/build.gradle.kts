plugins { id("com.android.library"); id("com.fataar"); `maven-publish` }
val scope = providers.gradleProperty("test.bomScope").orElse("api").get()
val omission = providers.gradleProperty("test.omitBom").isPresent
val bundleOnly = providers.gradleProperty("test.onlyEmbedded").isPresent
android { namespace = "com.fataar.bomfixture.sdk"; compileSdk = 36; defaultConfig { minSdk = 24 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
dependencies {
 "embed"(project(":feature"))
 "embed"("com.fataar.bomfixture:embedded-bundle:1")
 if (!omission) {
  val notation = "com.fataar.bomfixture:nested-bom:2"
  add(scope, if (providers.gradleProperty("test.enforced").isPresent) enforcedPlatform(notation) else platform(notation))
 }
 if (!bundleOnly) {
  // API platform supplies the version on both producer and consumer compile paths.
  // A runtime-only platform does not supply the published API variant; keep its API version explicit.
  api(if (scope == "api") "com.fataar.bomfixture:api-lib" else "com.fataar.bomfixture:api-lib:2")
  implementation("com.fataar.bomfixture:engine-lib")
 }
}
publishing { repositories.maven { name = "Sdk"; url = uri(rootProject.layout.buildDirectory.dir("sdk-repository")) } }
components.configureEach { if (name == "fatRelease") {
 val component = this
 publishing.publications.register<MavenPublication>("sdk") { from(component); artifactId = "sdk-" + scope }
} }
