package com.sensitivemobility.subatlas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Lien vers le document officiel dont sont tirées les fiches. */
internal const val SAFETY_SOURCE_URL = "https://rex.ffessm.fr/wp-content/uploads/2024/10/2024-10-Fiche-guide-Technique-Remontees-anormales.pdf"

/** Identifiant de la fiche « remontée rapide » (ouverte depuis la courbe d'une plongée). */
internal const val SAFETY_FAST_ASCENT = "fast-ascent"

/** Seuil de la définition fédérale d'une remontée rapide (m/min), entre 30 m et la surface, sur au moins 10 m. */
internal const val FAST_ASCENT_FFESSM_RATE = 15.0

/** Un bloc d'une fiche : un titre facultatif et des lignes, numérotées (étapes) ou à puces. */
class SafetySection(val heading: String?, val lines: List<String>, val numbered: Boolean = false)

/** Une fiche de sécurité : un résumé pour la liste et le détail en sections. */
class SafetyCard(val id: String, val title: String, val summary: String, val sections: List<SafetySection>)

@Composable
internal fun SafetyList(pad: PaddingValues, actions: PlatformActions, onOpen: (String) -> Unit) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.emergencyTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { actions.openLink("tel:112") }, modifier = Modifier.fillMaxWidth()) { Text(s.emergencyEurope) }
                Button(onClick = { actions.openLink("tel:15") }, modifier = Modifier.fillMaxWidth()) { Text(s.emergencySamu) }
                Button(onClick = { actions.openLink("tel:196") }, modifier = Modifier.fillMaxWidth()) { Text(s.emergencySea) }
            }
        }
        Text(s.safetyDisclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.safetyCards.forEach { card ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(card.id) },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                    Text(card.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun SafetyDetail(card: SafetyCard, pad: PaddingValues, actions: PlatformActions) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        card.sections.forEach { section ->
            section.heading?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            section.lines.forEachIndexed { i, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (section.numbered) "${i + 1}." else "•", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        Text(s.safetySourceNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { actions.openLink(SAFETY_SOURCE_URL) }, modifier = Modifier.fillMaxWidth()) { Text(s.safetyOpenSource) }
        Text(s.safetyDisclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
