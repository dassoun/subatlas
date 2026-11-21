package com.sensitivemobility.subatlas

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable
import kotlin.math.round

/** Système d'unités affiché (réglage). Les données restent enregistrées en métrique : seul l'affichage et la saisie changent. */
@Serializable
enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Conversion des mesures vers les unités du réglage : profondeur (m / ft), température (°C / °F), pression (bar / psi), volume de
 * bouteille (L / cuft), gaz consommé (L / cuft), SAC (L/min / cuft/min), lestage (kg / lb), vitesse de remontée (m/min / ft/min).
 *
 * Volume de bouteille : le litre est un volume d'eau, le pied cube d'une bouteille américaine un volume de gaz à sa pression de service ;
 * on convertit avec la convention de 3000 psi (une 11,1 L donne les 80 cuft d'une AL80). Le gaz consommé et le SAC sont des volumes de
 * gaz libre : 1 L = 0,0353147 cuft, sans ambiguïté.
 */
internal class Units(val system: UnitSystem) {
    val imperial: Boolean get() = system == UnitSystem.IMPERIAL

    /** Une grandeur : son unité, sa conversion et le nombre de décimales à l'affichage et à la saisie. */
    inner class Measure(
        metricUnit: String,
        imperialUnit: String,
        private val factor: Double,
        private val offset: Double = 0.0,
        private val metricDecimals: Int,
        private val imperialDecimals: Int,
        private val imperialInputDecimals: Int = imperialDecimals,
    ) {
        val unit: String = if (imperial) imperialUnit else metricUnit
        private val displayDecimals = if (imperial) imperialDecimals else metricDecimals
        private val inputDecimals = if (imperial) imperialInputDecimals else metricDecimals

        /** La valeur métrique [v] dans l'unité affichée, non arrondie. */
        fun toDisplay(v: Double): Double = if (imperial) v * factor + offset else v

        /** L'inverse : une valeur saisie dans l'unité affichée, en métrique. */
        fun toMetric(v: Double): Double = if (imperial) (v - offset) / factor else v

        private fun rounded(v: Double, decimals: Int): Double {
            var scale = 1.0
            repeat(decimals) { scale *= 10 }
            return round(v * scale) / scale
        }

        /** « 13.9 m », « 46 ft » ; [nbsp] colle l'unité au nombre (cartes, étiquettes courtes). */
        fun text(v: Double, nbsp: Boolean = false): String = formatNum(rounded(toDisplay(v), displayDecimals)) + (if (nbsp) " " else " ") + unit

        /** Le nombre seul, arrondi comme à l'affichage. */
        fun number(v: Double): String = formatNum(rounded(toDisplay(v), displayDecimals))

        /** Le texte d'un champ de saisie pour la valeur métrique [v] (vide si `null`). */
        fun input(v: Double?): String = v?.let { formatNum(rounded(toDisplay(it), inputDecimals)) } ?: ""

        /**
         * La valeur métrique d'un champ de saisie ; `null` si le texte n'est pas un nombre. Un texte inchangé rend la valeur d'origine
         * ([original]) : ouvrir puis enregistrer une plongée ne déforme pas ses données par un aller-retour d'arrondis.
         */
        fun fromInput(text: String, original: Double?): Double? {
            val parsed = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (original != null && text == input(original)) return original
            return toMetric(parsed)
        }
    }

    val depth = Measure("m", "ft", factor = 3.28084, metricDecimals = 2, imperialDecimals = 0, imperialInputDecimals = 1)
    val temperature = Measure("°C", "°F", factor = 1.8, offset = 32.0, metricDecimals = 2, imperialDecimals = 1)
    val pressure = Measure("bar", "psi", factor = 14.5038, metricDecimals = 0, imperialDecimals = 0)
    val tankVolume = Measure("L", "cuft", factor = 7.2092, metricDecimals = 2, imperialDecimals = 1)
    val gas = Measure("L", "cuft", factor = 0.0353147, metricDecimals = 0, imperialDecimals = 0)
    val sac = Measure("L/min", "cuft/min", factor = 0.0353147, metricDecimals = 1, imperialDecimals = 2)
    val weight = Measure("kg", "lb", factor = 2.20462, metricDecimals = 2, imperialDecimals = 1)
    val rate = Measure("m/min", "ft/min", factor = 3.28084, metricDecimals = 1, imperialDecimals = 0)

    /** Distance à vol d'oiseau jusqu'à un site : mètres et kilomètres, ou pieds et miles. */
    fun distance(meters: Double): String {
        if (!imperial) return formatDistance(meters)
        val feet = meters * 3.28084
        return if (feet < 1000) "${round(feet).toLong()} ft" else {
            val miles = meters / 1609.344
            if (miles < 100) "${round(miles * 10) / 10} mi" else "${round(miles).toLong()} mi"
        }
    }

    companion object {
        /** Le pied par mètre : sert au tracé des graphiques. */
        const val FEET_PER_METER = 3.28084
        const val PSI_PER_BAR = 14.5038
    }
}

/** Les unités en vigueur (réglage), lues par tous les écrans. */
internal val LocalUnits = staticCompositionLocalOf { Units(UnitSystem.METRIC) }
