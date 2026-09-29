plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.olivier.gpxroad.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.olivier.gpxroad"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "0.0.34"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.core)
    testImplementation(kotlin("test-junit"))
}
