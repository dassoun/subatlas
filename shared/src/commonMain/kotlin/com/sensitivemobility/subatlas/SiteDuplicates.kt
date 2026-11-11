package com.sensitivemobility.subatlas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Écran des sites en double : un groupe par ensemble de sites aux noms proches. */
@Composable
internal fun SiteDuplicatesScreen(state: AppState, pad: PaddingValues, onExport: () -> Unit) {
    val s = LocalStrings.current
    val groups = remember(state.sites.toList(), state.settings.distinctSites) { state.siteDuplicateGroups() }
    if (groups.isEmpty()) {
        Text(s.duplicatesNone, Modifier.padding(pad).padding(16.dp))
        return
    }
    LazyColumn(
        Modifier.padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(s.duplicatesHelp, style = MaterialTheme.typography.bodyMedium) }
        items(groups, key = { g -> g.joinToString("-") { it.id.toString() } }) { group ->
            DuplicateGroupCard(state, group, onExport)
        }
    }
}

@Composable
private fun DuplicateGroupCard(state: AppState, group: List<DiveSite>, onExport: () -> Unit) {
    val s = LocalStrings.current
    val ids = group.map { it.id }
    val counts = group.associate { site -> site.id to state.dives.count { it.siteId == site.id } }
    // À garder par défaut : le site localisé, puis celui qui a le plus de plongées.
    val defaultKeeper = group.sortedWith(
        compareByDescending<DiveSite> { it.latitude != null }.thenByDescending { counts[it.id] ?: 0 },
    ).first().id
    var keeperId by remember(ids) { mutableStateOf(defaultKeeper) }
    var included by remember(ids) { mutableStateOf(ids.toSet()) }
    var confirm by remember(ids) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            group.forEach { site ->
                Row(
                    Modifier.fillMaxWidth().clickable { keeperId = site.id; included = included + site.id },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Le site conservé fait forcément partie de la fusion.
                    Checkbox(
                        checked = site.id in included,
                        onCheckedChange = { on -> included = if (on) included + site.id else included - site.id },
                        enabled = site.id != keeperId,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(site.name, style = MaterialTheme.typography.titleSmall)
                        val la = site.latitude
                        val lo = site.longitude
                        val diveCount = counts[site.id] ?: 0
                        Text(
                            listOfNotNull(
                                site.countryText().takeIf { site.country.isNotBlank() },
                                if (la != null && lo != null) "📍 " + formatCoords(la, lo) else s.noPositionShort,
                                s.diveCount(diveCount).takeIf { diveCount > 0 },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    RadioButton(selected = site.id == keeperId, onClick = { keeperId = site.id; included = included + site.id })
                }
            }
            Text(s.mergeKeepHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val others = group.filter { it.id in included && it.id != keeperId }
            Button(enabled = others.isNotEmpty(), onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text(s.mergeSitesAction(others.size + 1))
            }
            TextButton(onClick = { state.markDistinct(group) }, modifier = Modifier.fillMaxWidth()) { Text(s.notDuplicates) }
        }
    }
    if (confirm) {
        val keeper = group.first { it.id == keeperId }
        val others = group.filter { it.id in included && it.id != keeperId }
        ConfirmMergeDialog(
            state, keeper, others, onExport,
            onConfirm = { state.mergeSites(keeperId, others.map { it.id }.toSet()); confirm = false },
            onDismiss = { confirm = false },
        )
    }
}

/** Confirmation d'une fusion : ce qui sera conservé, rattaché, supprimé, et la possibilité d'exporter d'abord. */
@Composable
internal fun ConfirmMergeDialog(
    state: AppState,
    keeper: DiveSite,
    others: List<DiveSite>,
    onExport: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val moved = state.dives.count { d -> others.any { it.id == d.siteId } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text("⚠️") },
        title = { Text(s.mergeSitesTitle, color = MaterialTheme.colorScheme.error) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.mergeSitesMessage(keeper.name, others.size, moved))
                TextButton(onClick = onExport) { Text(s.mergeExportFirst) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(s.mergeSitesConfirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Choix d'un autre site à fusionner dans [keeper] (depuis sa fiche) ; les plus ressemblants d'abord. */
@Composable
internal fun PickSiteToMergeDialog(state: AppState, keeper: DiveSite, onPick: (DiveSite) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val candidates = remember(state.sites.toList(), keeper.id) {
        state.sites.filter { it.id != keeper.id }
            .sortedWith(compareByDescending<DiveSite> { siteNameSimilarity(it.name, keeper.name) }.thenBy { it.name.lowercase() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.mergePickTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.mergePickHelp(keeper.name), style = MaterialTheme.typography.bodySmall)
                candidates.forEach { site ->
                    Card(
                        onClick = { onPick(site) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(site.name, style = MaterialTheme.typography.titleSmall)
                            val la = site.latitude
                            val lo = site.longitude
                            Text(
                                listOfNotNull(
                                    site.countryText().takeIf { site.country.isNotBlank() },
                                    if (la != null && lo != null) "📍 " + formatCoords(la, lo) else s.noPositionShort,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
