import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "com.olivier.gpxroad.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    // Framework STATIQUE : lié dans l'app iOS, rien à embarquer. Nom distinct du module Swift
    // `GPXroad` pour éviter toute ambiguïté d'import.
    val xcframework = XCFramework("GPXroadShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "GPXroadShared"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
