// Monorepo GPXroad (it32) : iosApp/ = projet Xcode (hors Gradle), shared/ = logique Kotlin
// Multiplatform, androidApp/ = app Android Compose.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GPXroad"
include(":shared", ":androidApp")
