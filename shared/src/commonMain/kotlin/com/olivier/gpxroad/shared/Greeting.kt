package com.olivier.gpxroad.shared

/** Fonction triviale de l'étape 0 (it32) : prouve la chaîne Kotlin → Swift et Kotlin → Compose. */
fun greeting(): String = "GPXroad shared ${platformName()}"

internal expect fun platformName(): String
