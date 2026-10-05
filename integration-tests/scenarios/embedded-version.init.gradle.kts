// The runtime graph selects v2 via a constraint, while embed declares v1.
// The archive merger must package the same v2 that ABI validation inspected.
gradle.settingsEvaluated {
    if (rootProject.name == "FatAarPlugin") {
        dependencyResolutionManagement.repositories.maven {
            url = uri(settingsDir.resolve("build/version-fixture-repository"))
            content { includeGroup("com.fataar.versionfixture") }
        }
    }
}
gradle.beforeProject {
    if (path == ":sdk") {
        afterEvaluate {
            dependencies.add("embed", "com.fataar.versionfixture:payload:1")
            dependencies.constraints.add("implementation", "com.fataar.versionfixture:payload:2")
        }
    }
}
