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
rootProject.name = "handeye"

include(":device-kmp")
include(":orchestrator")
include(":demo-android:app")
include(":demo-android:e2e")
include(":conformance")
