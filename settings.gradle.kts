rootProject.name = "mulcor"

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include("mulcor-memory", "mulcor-registry", "mulcor-storage", "mulcor-core", "mulcor-net", "mulcor-harness")
