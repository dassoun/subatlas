package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AppLanguage { SYSTEM, FR, EN }

/** Tri de la liste des plongées. */
@Serializable
enum class DiveSort { DATE_DESC, DATE_ASC, SITE, COUNTRY, DEPTH_DESC, DURATION_DESC, SAC_ASC }

/** Préférences de l'utilisateur (enregistrées dans `settings.json`, hors des sauvegardes de données). */
@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val diveSort: DiveSort = DiveSort.DATE_DESC,
)
