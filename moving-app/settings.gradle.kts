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
        // Robolectric's Android images (150 MB each) from Google's mirror of Maven Central, which does not turn
        // CI away with HTTP 429 the way Maven Central itself has. Only that group comes from the mirror.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}
rootProject.name = "HouseMove"
include(":app")
include(":shared")
// The screens shared with the iPhone app (Compose Multiplatform); the iPhone links them as HouseMoveKit.
include(":sharedUi")
