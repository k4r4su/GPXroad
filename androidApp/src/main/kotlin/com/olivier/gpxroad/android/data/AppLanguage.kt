package com.olivier.gpxroad.android.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Langue de l'app (`AppLanguage` iOS) : automatique (langue du téléphone) ou forcée. Appliquée à
 * l'activité au démarrage ([wrap]) ; un changement recrée l'activité.
 */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    AUTOMATIC(null, ""),
    FRENCH("fr", "Français"),
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano");

    companion object {
        private const val KEY = "appLanguage"

        fun current(context: Context): AppLanguage =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { name -> entries.firstOrNull { it.name == name } } ?: AUTOMATIC

        fun save(context: Context, value: AppLanguage) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, value.name).commit()
        }

        /** Contexte dans la langue choisie ; la langue par défaut suit (textes Valhalla, Nominatim, voix). */
        fun wrap(base: Context): Context {
            val tag = current(base).tag ?: return base
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocale(locale)
            return base.createConfigurationContext(configuration)
        }
    }
}
