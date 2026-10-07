import com.android.build.api.variant.BuildConfigField
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
        // Serveurs intégrés (APK de test PRIVÉ seulement) : JSON en base64 lu dans l'environnement du
        // build (jamais dans un fichier du dépôt) ; vide dans tous les autres builds. Lu par
        // `providers` pour que Gradle le suive : avec le cache de configuration, un simple
        // System.getenv() resterait figé sur la valeur du premier build (APK sans serveurs).
        variant.buildConfigFields?.put(
            "BUNDLED_SERVERS",
            providers.environmentVariable("GPXROAD_BUNDLED_SERVERS").orElse("").map { BuildConfigField("String", "\"$it\"", "serveurs intégrés (APK privé)") },
        )
    }
}

android {
    namespace = "com.olivier.gpxroad.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.olivier.gpxroad"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.0.39"
        // Tablette et émulateur de test : arm64 seulement (MapLibre embarque sinon 4 bibliothèques natives).
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            // APK à donner aux testeurs : non « debuggable », signé avec la clé de débogage (la même que
            // la tablette de test, donc installable par-dessus sans désinstaller). À remplacer par une clé
            // de publication avant un Play Store : changer de clé oblige chacun à réinstaller l'app.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.maplibre.android)
    testImplementation(kotlin("test-junit"))
    // Implémentation réelle d'org.json pour les tests JVM (celle d'android.jar n'est qu'un bouchon).
    testImplementation(libs.json)
}
