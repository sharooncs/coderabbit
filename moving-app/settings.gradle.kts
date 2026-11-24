pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "HouseMove"
include(":app")
include(":shared")
// The screens shared with the iPhone app (Compose Multiplatform); the iPhone links them as HouseMoveKit.
include(":sharedUi")
