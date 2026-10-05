// Negative test only: simulate accidentally moving an exposed library to implementation.
gradle.beforeProject {
    if (path == ":sdk") {
        afterEvaluate {
            val api = configurations.getByName("api")
            val dependency = api.dependencies.single { it.group == "androidx.constraintlayout" && it.name == "constraintlayout" }
            api.dependencies.remove(dependency)
            dependencies.add("implementation", dependency)
        }
    }
}
