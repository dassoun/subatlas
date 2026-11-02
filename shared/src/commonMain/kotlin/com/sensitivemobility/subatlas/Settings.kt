package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AppLanguage { SYSTEM, FR, EN }

/** Tri de la liste des plongées. */
@Serializable
enum class DiveSort { DATE, SITE, COUNTRY, DEPTH, DURATION, SAC }

/** Sens appliqué la première fois qu'on choisit ce critère ; un second appui inverse le tri. */
internal fun DiveSort.defaultDescending(): Boolean = when (this) {
    DiveSort.DATE, DiveSort.DEPTH, DiveSort.DURATION -> true
    DiveSort.SITE, DiveSort.COUNTRY, DiveSort.SAC -> false
}

/** Préférences de l'utilisateur (enregistrées dans `settings.json`, hors des sauvegardes de données). */
@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val diveSort: DiveSort = DiveSort.DATE,
    val diveSortDescending: Boolean = true,
)
