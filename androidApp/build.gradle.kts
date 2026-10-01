plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Style de carte vectoriel : le MÊME fichier que l'iPhone (`iosApp/GPXroad/Resources`), copié dans les
 * assets à la compilation — jamais une copie versionnée qui divergerait.
 */
abstract class CopyMapStyles : DefaultTask() {
    @get:InputFiles
    abstract val styles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile
        target.mkdirs()
        styles.files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

val copyMapStyles = tasks.register<CopyMapStyles>("copyMapStyles") {
    styles.from(rootProject.file("iosApp/GPXroad/Resources/vector-style-liberty.json"))
    // Trace d'exemple de l'accueil (même fichier que l'iPhone).
    styles.from(rootProject.file("iosApp/GPXroad/Resources/sample-trail.gpx"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyMapStyles, CopyMapStyles::outputDir)
    }
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
        // Tablette et émulateur de test : arm64 seulement (MapLibre embarque sinon 4 bibliothèques natives).
        ndk { abiFilters += "arm64-v8a" }
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
    implementation(libs.maplibre.android)
    testImplementation(kotlin("test-junit"))
    // Implémentation réelle d'org.json pour les tests JVM (celle d'android.jar n'est qu'un bouchon).
    testImplementation(libs.json)
}
