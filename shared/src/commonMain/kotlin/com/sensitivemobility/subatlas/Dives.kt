package com.sensitivemobility.subatlas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Instant

// ------------------------------------------------------------------ Outils

internal fun todayIso(): String = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()

private fun parseDate(iso: String): LocalDate? = runCatching { LocalDate.parse(iso) }.getOrNull()

internal fun Strings.dateText(iso: String): String = parseDate(iso)?.let(::formatDate) ?: iso

private fun nowTime(): Pair<Int, Int> {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return t.hour to t.minute
}

private fun timeText(hour: Int, minute: Int) = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/** Entier sans « .0 » ; sinon le nombre tel quel. */
internal fun formatNum(d: Double): String {
    // Arrondi à 2 décimales : écarte les artefacts de virgule flottante (9.900001) des données importées.
    val r = round(d * 100) / 100
    return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
}

/** Distance à vol d'oiseau en mètres (formule de haversine). */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    fun rad(d: Double) = d * PI / 180.0
    val dLat = rad(lat2 - lat1)
    val dLon = rad(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * 6_371_000.0 * asin(sqrt(a))
}

internal fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${round(meters).toLong()} m"
    meters < 100_000 -> round(meters / 100).toLong().let { "${it / 10}.${it % 10} km" }
    else -> "${round(meters / 1000).toLong()} km"
}

private fun Tank.gasLabel(s: Strings): String = when {
    hePercent > 0 -> "${s.gasTrimix} $o2Percent/$hePercent"
    o2Percent == 21 -> s.gasAir
    else -> "${s.gasNitrox} $o2Percent"
}

private fun Tank.pressureText(): String? = when {
    pressureStartBar != null && pressureEndBar != null -> "$pressureStartBar → $pressureEndBar bar"
    pressureStartBar != null -> "$pressureStartBar bar"
    else -> null
}

private fun Dive.timeRangeText(s: Strings): String? {
    val a = startTime
    val b = endTime
    val range = when {
        a != null && b != null -> "$a – $b"
        a != null -> a
        b != null -> "– $b"
        else -> return null
    }
    return durationMinutes?.let { "$range (${s.minutesShort(it)})" } ?: range
}

// ---------------------------------------------------------------- Liste

@Composable
internal fun DiveList(
    state: AppState,
    pad: PaddingValues,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onShared: (SharedDive) -> Unit,
    actions: PlatformActions,
) {
    val s = LocalStrings.current
    var pasting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<List<ShearwaterPlanItem>?>(null) }
    var planSkipped by remember { mutableStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var mergeAsk by remember { mutableStateOf<Pair<Dive, Dive>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Import d'un export Shearwater Cloud : on lit le fichier, on prépare la fusion, puis l'utilisateur confirme.
    fun startShearwaterImport() {
        scope.launch {
            try {
                val bytes = actions.openFileBytes() ?: return@launch
                reading = true
                // Un gros fichier se lit en quelques secondes : hors du fil principal pour ne pas figer l'écran.
                val file = try {
                    withContext(Dispatchers.Default) { ShearwaterImport.parse(bytes) }
                } finally {
                    reading = false
                }
                if (file.dives.isEmpty()) {
                    message = s.shearwaterNone
                } else {
                    planSkipped = file.skipped
                    plan = state.planShearwaterImport(file.dives)
                }
            } catch (e: ShearwaterImport.NotShearwaterException) {
                message = s.shearwaterInvalid
            } catch (e: Exception) {
                message = s.importFailed(e.message ?: s.unknownError)
            }
        }
    }
    val sort = state.settings.diveSort
    val descending = state.settings.diveSortDescending
    Column(Modifier.fillMaxSize().padding(pad)) {
        if (state.dives.isEmpty()) Text(s.noDives, modifier = Modifier.padding(16.dp))
        if (state.dives.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiveSort.entries.forEach { option ->
                    val active = sort == option
                    FilterChip(
                        selected = active,
                        // Un critère inactif s'active ; s'il est déjà actif, un nouvel appui inverse le sens.
                        onClick = {
                            state.updateSettings {
                                it.copy(
                                    diveSort = option,
                                    diveSortDescending = if (active) !descending else option.defaultDescending(),
                                )
                            }
                        },
                        // Flèche vers le bas : décroissant ; vers le haut : croissant.
                        label = { Text(s.sortLabel(option) + if (active) (if (descending) " \u2193" else " \u2191") else "") },
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.sortedDives(sort, descending), key = { it.id }) { dive ->
                Card(
                    onClick = { onOpen(dive.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = diveCardColor(dive.kind, state.settings)),
                ) {
                    Row(Modifier.height(IntrinsicSize.Min).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1.4f)) {
                            val previous = if (dive.noMerge) null else state.mergeCandidate(dive)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.dateText(dive.date), style = MaterialTheme.typography.titleMedium)
                                if (previous != null) {
                                    Spacer(Modifier.width(10.dp))
                                    MergeMenuChip(
                                        onMerge = { mergeAsk = previous to dive },
                                        onNoMerge = { state.setNoMerge(dive.id) },
                                    )
                                }
                            }
                            val diveSite = state.site(dive.siteId)
                            if (diveSite != null) SiteLine(diveSite, MaterialTheme.typography.bodyMedium, maxLines = 1) else Text("?")
                            Text(dive.listCompact(s), style = MaterialTheme.typography.bodySmall)
                        }
                        // La moitié droite de la carte, sur toute sa hauteur.
                        dive.profile?.let { ProfileSparkline(it, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(s.addDive) }
            TextButton(onClick = { pasting = true }, modifier = Modifier.fillMaxWidth()) { Text(s.pasteSharedButton) }
            TextButton(onClick = { startShearwaterImport() }, modifier = Modifier.fillMaxWidth()) { Text(s.importShearwater) }
        }
    }
    if (pasting) PasteSharedDialog(onDismiss = { pasting = false }) { pasting = false; onShared(it) }
    mergeAsk?.let { (earlier, later) -> MergeDialog(state, earlier, later) { mergeAsk = null } }
    if (reading) ReadingDialog()
    plan?.let { items ->
        ShearwaterPlanDialog(
            items,
            planSkipped,
            onConfirm = {
                val (created, merged) = state.applyShearwaterImport(items)
                plan = null
                message = s.shearwaterDone(created, merged)
            },
            onDismiss = { plan = null },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(s.ok) } },
        )
    }
}

/** Confirmation de la fusion de [later] dans [earlier]. */
@Composable
internal fun MergeDialog(state: AppState, earlier: Dive, later: Dive, onDone: () -> Unit) {
    val s = LocalStrings.current
    fun label(d: Dive) = "${s.dateText(d.date)} ${d.timeRangeText(s).orEmpty()}".trim()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(s.mergeTitle) },
        text = { Text(s.mergeMessage(label(earlier), label(later))) },
        confirmButton = { TextButton(onClick = { state.mergeWithPrevious(later.id); onDone() }) { Text(s.mergeAction) } },
        dismissButton = { TextButton(onClick = onDone) { Text(s.cancel) } },
    )
}

/** Message d'attente pendant la lecture d'un gros fichier. */
@Composable
internal fun ReadingDialog() {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(s.shearwaterReading)
            }
        },
        confirmButton = {},
    )
}

/** Aperçu de l'import : ce qui sera ajouté et ce qui sera fusionné avec une plongée du carnet. */
@Composable
internal fun ShearwaterPlanDialog(items: List<ShearwaterPlanItem>, skipped: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val merged = items.count { it.mergeInto != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.shearwaterTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.shearwaterFound(items.size, items.size - merged, merged))
                // Un gros fichier : on montre les premières plongées, le résumé du dessus donne les totaux.
                items.take(12).forEach { item ->
                    val sw = item.dive
                    Column {
                        Text(
                            listOf(s.dateText(sw.date), sw.startTime.orEmpty(), "·", sw.siteName).filter { it.isNotEmpty() }.joinToString(" "),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val details = listOfNotNull(
                            sw.maxDepth?.let { "max\u00A0${formatNum(it)}\u00A0m" },
                            sw.durationSeconds?.let { s.minutesShort(it / 60) },
                            if (item.mergeInto != null) s.shearwaterMergeTag else s.shearwaterNewTag,
                        )
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.mergeInto != null) StatusWarn else StatusOk,
                        )
                    }
                }
                if (items.size > 12) Text(s.shearwaterMore(items.size - 12), style = MaterialTheme.typography.bodyMedium)
                if (skipped > 0) Text(s.shearwaterSkipped(skipped), style = MaterialTheme.typography.bodySmall, color = StatusWarn)
                if (merged > 0) Text(s.shearwaterMergeHelp, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(s.shearwaterConfirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/**
 * Tri choisi pour la liste. Les plongées sans valeur pour le critère (pas de SAC, de durée…) restent à la fin quel que
 * soit le sens ; à égalité, la plus récente d'abord.
 */
private fun AppState.sortedDives(sort: DiveSort, descending: Boolean): List<Dive> {
    fun <K : Comparable<K>> by(key: (Dive) -> K?): List<Dive> {
        val (known, unknown) = dives.sortedWith(diveOrder).partition { key(it) != null }
        val ascending = compareBy<Dive> { key(it)!! }
        return known.sortedWith(if (descending) ascending.reversed() else ascending) + unknown
    }
    fun siteName(d: Dive) = site(d.siteId)?.name?.lowercase().orEmpty()
    fun country(d: Dive) = site(d.siteId)?.country?.lowercase().orEmpty()
    return when (sort) {
        DiveSort.DATE -> by { it.date + " " + it.startTime.orEmpty() }
        DiveSort.SITE -> by { siteName(it).takeIf { n -> n.isNotBlank() } }
        DiveSort.COUNTRY -> by { d -> country(d).takeIf { it.isNotBlank() }?.let { it + "|" + siteName(d) } }
        DiveSort.DEPTH -> by { it.maxDepth }
        DiveSort.DURATION -> by { it.durationMinutes }
        DiveSort.SAC -> by { it.sacLitersPerMin }
    }
}

private fun Dive.suitSummary(s: Strings): String? {
    val type = suitType
    return if (type != null) {
        listOfNotNull(s.suitType(type), suitThicknessMm?.let { "${formatNum(it)} mm" }).joinToString(" ")
    } else {
        suit.takeIf { it.isNotBlank() }
    }
}

/** Ligne de résumé de la liste : horaires, profondeur max, eau, combinaison, volumes, lestage. */
/** Résumé de la liste : durée et profondeur seulement. */
private fun Dive.listCompact(s: Strings): String = listOfNotNull(
    durationMinutes?.let { s.minutesShort(it) },
    maxDepth?.let { "max ${formatNum(it)} m" },
).joinToString(" · ")

private fun Dive.listSummary(s: Strings): String {
    val volumes = tanks.mapNotNull { it.volumeLiters }.takeIf { it.isNotEmpty() }
        ?.joinToString(" + ") { formatNum(it) }?.let { "$it\u00A0L" }
    return listOfNotNull(
        timeRangeText(s),
        maxDepth?.let { "max\u00A0${formatNum(it)}\u00A0m" },
        waterTempC?.let { "${formatNum(it)}\u00A0°C" },
        suitSummary(s),
        volumes,
        weightKg?.let { "${formatNum(it)}\u00A0kg" },
        sacLitersPerMin?.let { "SAC\u00A0${formatNum(round(it * 10) / 10)}\u00A0L/min" },
    ).joinToString(" · ")
}

/** Plongées d'un site, pour la fiche du site. */
@Composable
internal fun SiteDivesSection(dives: List<Dive>, onOpen: (Long) -> Unit) {
    val s = LocalStrings.current
    if (dives.isEmpty()) return
    Text(
        "${s.siteDivesTitle} (${dives.size})",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    dives.forEach { d ->
        Card(
            onClick = { onOpen(d.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(s.dateText(d.date), style = MaterialTheme.typography.titleSmall)
                val details = listOfNotNull(
                    d.timeRangeText(s),
                    d.maxDepth?.let { "max ${formatNum(it)} m" },
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// --------------------------------------------------------------- Détail

@Composable
internal fun DiveDetailView(
    dive: Dive,
    state: AppState,
    pad: PaddingValues,
    actions: PlatformActions,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenSite: (Long) -> Unit,
) {
    val s = LocalStrings.current
    var confirm by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val site = state.site(dive.siteId)
    val previousDive = state.mergeCandidate(dive)
    val recipients = dive.shareRecipients
    val canShare = site != null && recipients.isNotEmpty()
    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(s.dateText(dive.date), style = MaterialTheme.typography.headlineSmall)
        if (site != null) {
            TextButton(onClick = { onOpenSite(site.id) }) {
                SiteLine(site, MaterialTheme.typography.titleMedium)
            }
        }
        dive.kind?.let { k ->
            val kindText = listOfNotNull(s.diveKind(k), dive.kindOther.takeIf { k == DiveKind.OTHER && it.isNotBlank() })
            Text(s.labelled(s.diveKindLabel, kindText.joinToString(" · ")))
        }
        dive.timeRangeText(s)?.let { Text(it) }
        dive.durationMinutes?.let { Text(s.labelled(s.durationLabel, s.minutesShort(it))) }
        dive.maxDepth?.let { Text(s.labelled(s.depthLabel, "${formatNum(it)} m")) }
        dive.avgDepthMeters?.let { Text(s.labelled(s.avgDepthLabel, "${formatNum(it)} m")) }
        dive.profile?.let { ProfileChart(it, Modifier.fillMaxWidth(), onFullscreen = { fullscreen = true }) }

        dive.tanks.forEachIndexed { i, tank ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${s.tankTitle(i + 1)} · ${tank.gasLabel(s)}", style = MaterialTheme.typography.titleSmall)
                    tank.volumeLiters?.let { Text(s.labelled(s.volume, "${formatNum(it)} L")) }
                    tank.pressureText()?.let { Text(it) }
                    tank.avgDepthMeters?.let { Text(s.labelled(s.avgDepthLabel, "${formatNum(it)} m")) }
                    tank.usedMinutes?.let { Text(s.labelled(s.usedMinutesLabel, s.minutesShort(it))) }
                    tank.consumedLiters?.let { Text(s.consumedLine(round(it).toInt())) }
                    // Avec plusieurs bouteilles, chacune a son SAC (si on connaît sa durée d'utilisation).
                    if (dive.tanks.size > 1) {
                        tank.sacLitersPerMin(null, dive.avgDepthMeters)?.let {
                            Text(s.labelled(s.sacLabel, "${formatNum(round(it * 10) / 10)} L/min"), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        dive.sacLitersPerMin?.let {
            Text(
                s.labelled(s.sacLabel, "${formatNum(round(it * 10) / 10)} L/min"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        dive.waterTempC?.let { Text(s.labelled(s.waterTemp, "${formatNum(it)} °C")) }
        dive.visibilityMeters?.let { Text(s.labelled(s.visibility, "$it m")) }
        dive.weightKg?.let { Text(s.labelled(s.weight, "${formatNum(it)} kg")) }
        val suitText = listOfNotNull(
            dive.suitType?.let { s.suitType(it) },
            dive.suitThicknessMm?.let { "${formatNum(it)} mm" },
            dive.suit.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (suitText.isNotEmpty()) Text(s.labelled(s.suit, suitText))
        if (dive.buddy.isNotBlank()) Text(s.labelled(s.buddy, dive.buddy))
        if (dive.group.isNotEmpty()) Text(s.labelled(s.groupSection, dive.group.joinToString(", ") { it.name }))
        if (dive.computer.isNotBlank()) Text(s.labelled(s.computerLabel, dive.computer))
        if (dive.notes.isNotBlank()) Text(s.notesLine(dive.notes))

        val lat = site?.latitude
        val lon = site?.longitude
        if (site != null && lat != null && lon != null) {
            OutlinedButton(onClick = { actions.openMap(lat, lon, site.name) }, modifier = Modifier.fillMaxWidth()) {
                Text(s.openInMaps)
            }
        }
        if (canShare) {
            OutlinedButton(onClick = { sharing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (recipients.size == 1) s.shareButton(recipients[0].name) else s.shareWithPeople)
            }
        }
        // Dans la fiche, la fusion reste proposée même si on a refusé qu'elle le soit dans la liste.
        if (previousDive != null) {
            OutlinedButton(onClick = { merging = true }, modifier = Modifier.fillMaxWidth()) {
                MergeIcon(crossed = false)
                Spacer(Modifier.width(8.dp))
                Text(s.mergePrevious)
            }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(s.edit) }
        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(s.delete) }
    }
    if (sharing && site != null) ShareDiveDialog(dive, site, actions, recipients) { sharing = false }
    if (merging && previousDive != null) MergeDialog(state, previousDive, dive) { merging = false }
    dive.profile?.let { if (fullscreen) ProfileFullscreen(it, actions) { fullscreen = false } }
    if (confirm) {
        ConfirmDialog(
            s.confirmDeleteDive(site?.name ?: "?", s.dateText(dive.date)),
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}

// ------------------------------------------------------------ Formulaire

/** Saisie d'une bouteille, sous forme de texte tant que le formulaire est ouvert. */
private data class TankForm(
    val volume: String = "",
    val o2: String = "21",
    val he: String = "0",
    val pStart: String = "",
    val pEnd: String = "",
    val avgDepth: String = "",
    val minutes: String = "",
) {
    private val o2Val get() = o2.trim().toIntOrNull()
    private val heVal get() = he.trim().toIntOrNull()

    val volumeOk get() = volume.isBlank() || volume.toDec() != null
    val o2Ok get() = o2Val?.let { it in 1..100 } == true
    val heOk get() = heVal?.let { it in 0..99 && it + (o2Val ?: 0) <= 100 } == true
    val pStartOk get() = pStart.isBlank() || pStart.trim().toIntOrNull() != null
    val pEndOk get() = pEnd.isBlank() || pEnd.trim().toIntOrNull() != null
    val avgDepthOk get() = avgDepth.isBlank() || avgDepth.toDec() != null
    val minutesOk get() = minutes.isBlank() || (minutes.trim().toIntOrNull() ?: 0) > 0
    val valid get() = volumeOk && o2Ok && heOk && pStartOk && pEndOk && avgDepthOk && minutesOk

    fun toTank() = Tank(
        volumeLiters = volume.toDec(),
        o2Percent = o2Val ?: 21,
        hePercent = heVal ?: 0,
        pressureStartBar = pStart.trim().toIntOrNull(),
        pressureEndBar = pEnd.trim().toIntOrNull(),
        avgDepthMeters = avgDepth.toDec(),
        usedMinutes = minutes.trim().toIntOrNull(),
    )
}

private fun Tank.toForm() = TankForm(
    volume = volumeLiters?.let(::formatNum) ?: "",
    o2 = o2Percent.toString(),
    he = hePercent.toString(),
    pStart = pressureStartBar?.toString() ?: "",
    pEnd = pressureEndBar?.toString() ?: "",
    avgDepth = avgDepthMeters?.let(::formatNum) ?: "",
    minutes = usedMinutes?.toString() ?: "",
)

/** Saisie d'une personne de la palanquée. */
private data class PersonForm(
    val id: Long,
    val name: String = "",
    val fromContacts: Boolean = false,
    val phone: String = "",
    val email: String = "",
    /** Nom choisi dans les suggestions : s'il est modifié ensuite, le contact est détaché. */
    val picked: String? = null,
) {
    val canShare get() = name.isNotBlank() && fromContacts && (phone.isNotBlank() || email.isNotBlank())
    fun toPerson() = Person(name.trim(), fromContacts, phone, email)
}

private fun String.toDec(): Double? = trim().replace(',', '.').toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiveForm(
    state: AppState,
    existing: Dive?,
    presetSiteId: Long?,
    location: LocationProvider,
    actions: PlatformActions,
    contacts: ContactsProvider,
    pad: PaddingValues,
    onDone: () -> Unit,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var siteId by remember { mutableStateOf(existing?.siteId ?: presetSiteId) }
    var date by remember { mutableStateOf(existing?.date ?: todayIso()) }
    var startTime by remember { mutableStateOf(existing?.startTime) }
    var endTime by remember { mutableStateOf(existing?.endTime) }
    val tanks = remember {
        // Une nouvelle plongée reprend le volume et le mélange de la dernière bouteille utilisée.
        val last = state.dives.sortedWith(diveOrder).firstOrNull()?.tanks?.firstOrNull()
        mutableStateListOf<TankForm>().apply {
            val initial = existing?.tanks?.map { it.toForm() }?.takeIf { it.isNotEmpty() }
                ?: listOf(last?.let { TankForm(volume = it.volumeLiters?.let(::formatNum) ?: "", o2 = it.o2Percent.toString(), he = it.hePercent.toString()) } ?: TankForm(volume = "12"))
            addAll(initial)
        }
    }
    var maxDepth by remember { mutableStateOf(existing?.maxDepth?.let(::formatNum) ?: "") }
    var avgDepth by remember { mutableStateOf(existing?.avgDepthMeters?.let(::formatNum) ?: "") }
    var kind by remember { mutableStateOf(existing?.kind) }
    var kindOther by remember { mutableStateOf(existing?.kindOther ?: "") }
    val group = remember {
        mutableStateListOf<PersonForm>().apply {
            existing?.group?.forEach { add(PersonForm(state.newId(), it.name, it.fromContacts, it.phone, it.email, it.name)) }
        }
    }
    var shareGroupAfter by remember { mutableStateOf(false) }
    var shareList by remember { mutableStateOf<List<Person>>(emptyList()) }
    var waterTemp by remember { mutableStateOf(existing?.waterTempC?.let(::formatNum) ?: "") }
    var visibility by remember { mutableStateOf(existing?.visibilityMeters?.toString() ?: "") }
    var weight by remember { mutableStateOf(existing?.weightKg?.let(::formatNum) ?: "") }
    var suitType by remember { mutableStateOf(existing?.suitType) }
    var thickness by remember { mutableStateOf(existing?.suitThicknessMm?.let(::formatNum) ?: "") }
    var buddy by remember { mutableStateOf(existing?.buddy ?: "") }
    var buddyFromContacts by remember { mutableStateOf(existing?.buddyFromContacts ?: false) }
    var buddyPhone by remember { mutableStateOf(existing?.buddyPhone ?: "") }
    var buddyEmail by remember { mutableStateOf(existing?.buddyEmail ?: "") }
    var pickedBuddy by remember { mutableStateOf(existing?.buddy) }
    var shareAfter by remember { mutableStateOf(false) }
    var sharedDive by remember { mutableStateOf<Dive?>(null) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    // Position GPS : sert à proposer les sites proches et à localiser un site créé ici.
    var position by remember { mutableStateOf<LocationResult?>(null) }
    var locating by remember { mutableStateOf(existing == null) }
    fun refreshPosition() {
        locating = true
        scope.launch {
            position = location.currentLocation()
            locating = false
        }
    }
    LaunchedEffect(Unit) {
        if (existing == null) {
            position = location.currentLocation()
            locating = false
        }
    }
    val here = position as? LocationResult.Success

    var siteMenu by remember { mutableStateOf(false) }
    var creatingSite by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<Boolean?>(null) } // true : début, false : fin
    var attachPosition by remember { mutableStateOf(false) }

    val selectedSite = siteId?.let(state::site)
    val sortedSites = state.sites.sortedWith(
        if (here != null) {
            compareBy<DiveSite> { site ->
                val la = site.latitude
                val lo = site.longitude
                if (la != null && lo != null) distanceMeters(here.latitude, here.longitude, la, lo) else Double.MAX_VALUE
            }.thenBy { it.name.lowercase() }
        } else {
            compareBy { it.name.lowercase() }
        },
    )

    val extrasOk = (maxDepth.isBlank() || maxDepth.toDec() != null) &&
        (avgDepth.isBlank() || avgDepth.toDec() != null) &&
        (waterTemp.isBlank() || waterTemp.toDec() != null) &&
        (visibility.isBlank() || visibility.trim().toIntOrNull() != null) &&
        (weight.isBlank() || weight.toDec() != null) &&
        (thickness.isBlank() || thickness.toDec() != null)
    val valid = selectedSite != null && tanks.all { it.valid } && extrasOk

    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // --- Site
        ExposedDropdownMenuBox(expanded = siteMenu, onExpandedChange = { siteMenu = it }) {
            OutlinedTextField(
                value = selectedSite?.fullName() ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(s.diveSite) },
                placeholder = { Text(s.chooseSite) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(siteMenu) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = siteMenu, onDismissRequest = { siteMenu = false }) {
                DropdownMenuItem(
                    text = { Text(s.createSite, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                    onClick = { siteMenu = false; creatingSite = true },
                )
                sortedSites.forEach { site ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(site.fullName())
                                val la = site.latitude
                                val lo = site.longitude
                                val sub = if (here != null && la != null && lo != null) {
                                    s.distanceAway(formatDistance(distanceMeters(here.latitude, here.longitude, la, lo)))
                                } else if (la == null || lo == null) {
                                    s.noPositionShort
                                } else {
                                    null
                                }
                                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { siteId = site.id; siteMenu = false; attachPosition = false },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    locating -> s.searchingPosition
                    here != null -> s.nearbySites
                    existing != null -> s.sitesByName
                    else -> "${s.positionNotFound}. ${s.sitesByName}"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (!locating && here == null) TextButton(onClick = { refreshPosition() }) { Text(s.retry) }
        }
        if (selectedSite != null && selectedSite.latitude == null && here != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(attachPosition, { attachPosition = it })
                Text(s.saveMyPosition, Modifier.clickable { attachPosition = !attachPosition })
            }
        }

        // --- Date et heures
        PickerField(s.diveDate, s.dateText(date), Modifier.fillMaxWidth()) { pickingDate = true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField(s.startTime, startTime ?: s.notSet, Modifier.weight(1f)) { pickingTime = true }
            PickerField(s.endTime, endTime ?: s.notSet, Modifier.weight(1f)) { pickingTime = false }
        }

        // --- Type de plongée (et palanquée pour l'encadrement et l'enseignement)
        Text(s.diveKindLabel, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiveKind.entries.forEach { k ->
                FilterChip(
                    selected = kind == k,
                    // Re-toucher le choix en cours le retire.
                    onClick = { kind = if (kind == k) null else k },
                    label = { Text(s.diveKind(k)) },
                )
            }
        }
        if (kind == DiveKind.OTHER) {
            OutlinedTextField(kindOther, { kindOther = it }, label = { Text(s.kindOtherField) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (kind?.hasGroup == true) {
            Text(s.groupSection, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            group.forEachIndexed { i, person ->
                key(person.id) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) {
                            BuddyField(
                                state = state,
                                contacts = contacts,
                                name = person.name,
                                label = s.personField,
                                onName = {
                                    // Retaper le nom après un choix dans le répertoire détache le contact.
                                    val same = it == person.picked
                                    group[i] = person.copy(
                                        name = it,
                                        fromContacts = same && person.fromContacts,
                                        phone = if (same) person.phone else "",
                                        email = if (same) person.email else "",
                                    )
                                },
                                onPick = { name, phone, email, fromContacts ->
                                    group[i] = person.copy(name = name, picked = name, fromContacts = fromContacts, phone = phone, email = email)
                                },
                            )
                        }
                        TextButton(onClick = { group.removeAt(i) }) { Text("\u2715") }
                    }
                }
            }
            OutlinedButton(onClick = { group.add(PersonForm(state.newId())) }, modifier = Modifier.fillMaxWidth()) {
                Text(s.addPerson)
            }
            if (group.any { it.canShare }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(shareGroupAfter, { shareGroupAfter = it })
                    Text(s.shareGroupCheckbox, Modifier.clickable { shareGroupAfter = !shareGroupAfter })
                }
            }
        }

        // --- Profondeurs
        NumField(s.depthLabel, maxDepth, { maxDepth = it }, Modifier.fillMaxWidth(), error = maxDepth.isNotBlank() && maxDepth.toDec() == null, unit = "m")
        NumField(s.avgDepthLabel, avgDepth, { avgDepth = it }, Modifier.fillMaxWidth(), error = avgDepth.isNotBlank() && avgDepth.toDec() == null, unit = "m")
        Text(s.avgDepthHint, style = MaterialTheme.typography.bodySmall)

        // --- Bouteilles
        Text(s.tanksSection, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        tanks.forEachIndexed { i, t ->
            TankEditor(
                number = i + 1,
                tank = t,
                canRemove = tanks.size > 1,
                showMinutes = tanks.size > 1,
                onChange = { tanks[i] = it },
                onRemove = { tanks.removeAt(i) },
            )
        }
        OutlinedButton(
            onClick = {
                val prev = tanks.last()
                tanks.add(TankForm(volume = prev.volume, o2 = prev.o2, he = prev.he))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.addTank) }

        // --- Conditions et matériel
        Text(s.conditionsSection, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        NumField(s.waterTemp, waterTemp, { waterTemp = it }, Modifier.fillMaxWidth(), error = waterTemp.isNotBlank() && waterTemp.toDec() == null, unit = "°C")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(s.visibility, visibility, { visibility = it }, Modifier.weight(1f), decimal = false, error = visibility.isNotBlank() && visibility.trim().toIntOrNull() == null, unit = "m")
            NumField(s.weight, weight, { weight = it }, Modifier.weight(1f), error = weight.isNotBlank() && weight.toDec() == null, unit = "kg")
        }
        Text(s.suit, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuitType.entries.forEach { type ->
                FilterChip(
                    selected = suitType == type,
                    // Re-toucher le choix en cours le retire.
                    onClick = { suitType = if (suitType == type) null else type },
                    label = { Text(s.suitType(type)) },
                )
            }
        }
        // Pas d'épaisseur à donner pour une combinaison étanche.
        if (suitType?.hasThickness == true) {
            NumField(s.thickness, thickness, { thickness = it }, Modifier.fillMaxWidth(), error = thickness.isNotBlank() && thickness.toDec() == null, unit = "mm")
        }
        BuddyField(
            state = state,
            contacts = contacts,
            name = buddy,
            onName = {
                buddy = it
                // Retaper le nom après un choix dans le répertoire détache le contact.
                if (it != pickedBuddy) {
                    buddyFromContacts = false
                    buddyPhone = ""
                    buddyEmail = ""
                    shareAfter = false
                }
            },
            onPick = { name, phone, email, fromContacts ->
                buddy = name
                pickedBuddy = name
                buddyFromContacts = fromContacts
                buddyPhone = phone
                buddyEmail = email
                shareAfter = false
            },
        )
        if (buddyFromContacts && buddy.isNotBlank() && (buddyPhone.isNotBlank() || buddyEmail.isNotBlank())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(shareAfter, { shareAfter = it })
                Text(s.shareWithCheckbox(buddy.trim()), Modifier.clickable { shareAfter = !shareAfter })
            }
        }
        OutlinedTextField(notes, { notes = it }, label = { Text(s.diveNotesField) }, minLines = 3, modifier = Modifier.fillMaxWidth())

        Button(
            enabled = valid,
            onClick = {
                val site = selectedSite!!
                if (attachPosition && here != null && site.latitude == null) {
                    state.saveSite(site.copy(latitude = here.latitude, longitude = here.longitude))
                }
                val dive = Dive(
                        id = existing?.id ?: state.newId(),
                        siteId = site.id,
                        date = date,
                        startTime = startTime,
                        endTime = endTime,
                        tanks = tanks.map { it.toTank() },
                        maxDepthMeters = maxDepth.toDec(),
                        avgDepthMeters = avgDepth.toDec(),
                        waterTempC = waterTemp.toDec(),
                        visibilityMeters = visibility.trim().toIntOrNull(),
                        weightKg = weight.toDec(),
                        suit = existing?.suit.orEmpty(),
                        suitType = suitType,
                        suitThicknessMm = if (suitType?.hasThickness == true) thickness.toDec() else null,
                        buddy = buddy.trim(),
                        buddyFromContacts = buddyFromContacts,
                        buddyPhone = buddyPhone,
                        buddyEmail = buddyEmail,
                        notes = notes.trim(),
                        kind = kind,
                        kindOther = if (kind == DiveKind.OTHER) kindOther.trim() else "",
                        group = if (kind?.hasGroup == true) group.filter { it.name.isNotBlank() }.map { it.toPerson() } else emptyList(),
                        // Ce que le formulaire ne montre pas (données de l'ordinateur de plongée) doit survivre à la modification.
                        computer = existing?.computer.orEmpty(),
                        importKey = existing?.importKey.orEmpty(),
                        noMerge = existing?.noMerge ?: false,
                        profile = existing?.profile,
                    )
                state.saveDive(dive)
                // Qui proposer de joindre : le binôme et/ou la palanquée, selon les cases cochées.
                val toShare = (if (shareAfter) dive.shareRecipients.filter { it.name == dive.buddy } else emptyList()) +
                    (if (shareGroupAfter) dive.group.filter { it.phone.isNotBlank() || it.email.isNotBlank() } else emptyList())
                if (toShare.isNotEmpty()) {
                    shareList = toShare
                    sharedDive = dive
                } else {
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.save) }
    }

    sharedDive?.let { d ->
        val diveSite = state.site(d.siteId)
        if (diveSite == null) onDone() else ShareDiveDialog(d, diveSite, actions, shareList) { sharedDive = null; onDone() }
    }
    if (creatingSite) {
        NewSiteDialog(
            here = here,
            defaultCountry = state.sites.lastOrNull { it.country.isNotBlank() }?.country.orEmpty(),
            countries = state.sites.map { it.country }.filter { it.isNotBlank() }.distinct(),
            onDismiss = { creatingSite = false },
            onCreate = { name, country, type, withPosition ->
                val site = DiveSite(
                    id = state.newId(),
                    name = name,
                    country = country,
                    type = type,
                    latitude = here?.latitude.takeIf { withPosition },
                    longitude = here?.longitude.takeIf { withPosition },
                )
                state.saveSite(site)
                siteId = site.id
                attachPosition = false
                creatingSite = false
            },
        )
    }
    if (pickingDate) {
        DateDialog(date, onDismiss = { pickingDate = false }) { date = it.toString(); pickingDate = false }
    }
    pickingTime?.let { isStart ->
        val current = if (isStart) startTime else endTime
        TimeDialog(
            initial = current,
            onDismiss = { pickingTime = null },
            onClear = { if (isStart) startTime = null else endTime = null; pickingTime = null },
        ) { h, m ->
            if (isStart) startTime = timeText(h, m) else endTime = timeText(h, m)
            pickingTime = null
        }
    }
}

@Composable
private fun PickerField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // Un champ en lecture seule absorbe les appuis : on pose une zone cliquable par-dessus.
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    decimal: Boolean = true,
    error: Boolean = false,
    unit: String? = null,
) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label, maxLines = 1) },
        suffix = unit?.let { { Text(it) } },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun TankEditor(
    number: Int,
    tank: TankForm,
    canRemove: Boolean,
    showMinutes: Boolean,
    onChange: (TankForm) -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalStrings.current
    val kind = when {
        (tank.he.trim().toIntOrNull() ?: 0) > 0 -> 2
        (tank.o2.trim().toIntOrNull() ?: 21) != 21 -> 1
        else -> 0
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.tankTitle(number), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (canRemove) TextButton(onClick = onRemove) { Text(s.removeTank, color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(s.volume, tank.volume, { onChange(tank.copy(volume = it)) }, Modifier.weight(1f), error = !tank.volumeOk, unit = "L")
                NumField(s.avgDepthShort, tank.avgDepth, { onChange(tank.copy(avgDepth = it)) }, Modifier.weight(1f), error = !tank.avgDepthOk, unit = "m")
            }
            if (showMinutes) {
                NumField(
                    s.usedMinutesLabel, tank.minutes, { onChange(tank.copy(minutes = it.filter(Char::isDigit))) },
                    Modifier.fillMaxWidth(), decimal = false, error = !tank.minutesOk, unit = "min",
                )
            }
            Text(s.gas, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == 0, { onChange(tank.copy(o2 = "21", he = "0")) }, label = { Text(s.gasAir) })
                FilterChip(
                    kind == 1,
                    { onChange(tank.copy(o2 = if (tank.o2.trim() == "21") "32" else tank.o2, he = "0")) },
                    label = { Text(s.gasNitrox) },
                )
                FilterChip(
                    kind == 2,
                    { onChange(tank.copy(he = if ((tank.he.trim().toIntOrNull() ?: 0) > 0) tank.he else "35")) },
                    label = { Text(s.gasTrimix) },
                )
            }
            if (kind >= 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(s.o2Field, tank.o2, { onChange(tank.copy(o2 = it.filter(Char::isDigit))) }, Modifier.weight(1f), decimal = false, error = !tank.o2Ok)
                    if (kind == 2) {
                        NumField(s.heField, tank.he, { onChange(tank.copy(he = it.filter(Char::isDigit))) }, Modifier.weight(1f), decimal = false, error = !tank.heOk)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(s.pressureStart, tank.pStart, { onChange(tank.copy(pStart = it.filter(Char::isDigit))) }, Modifier.weight(1f), decimal = false, error = !tank.pStartOk, unit = "bar")
                NumField(s.pressureEnd, tank.pEnd, { onChange(tank.copy(pEnd = it.filter(Char::isDigit))) }, Modifier.weight(1f), decimal = false, error = !tank.pEndOk, unit = "bar")
            }
        }
    }
}

@Composable
private fun NewSiteDialog(
    here: LocationResult.Success?,
    defaultCountry: String,
    countries: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String, SiteType?, Boolean) -> Unit,
) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(defaultCountry) }
    var type by remember { mutableStateOf<SiteType?>(null) }
    var withPosition by remember { mutableStateOf(here != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.newSite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(s.siteName) }, singleLine = true)
                CountryField(country, { country = it }, countries)
                SiteTypeChips(type) { type = it }
                if (here != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(withPosition, { withPosition = it })
                        Text(s.saveMyPosition, Modifier.clickable { withPosition = !withPosition })
                    }
                }
                Text(s.positionOptionalHint, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), country.trim(), type, withPosition && here != null) }) {
                Text(s.ok)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(iso: String, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val s = LocalStrings.current
    val initial = parseDate(iso) ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date)
                } ?: onDismiss()
            }) { Text(s.ok) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    ) { DatePicker(pickerState) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: String?, onDismiss: () -> Unit, onClear: () -> Unit, onPick: (Int, Int) -> Unit) {
    val s = LocalStrings.current
    val minutes = minutesOfDay(initial)
    val (nowH, nowM) = remember { nowTime() }
    val pickerState = rememberTimePickerState(
        initialHour = minutes?.div(60) ?: nowH,
        initialMinute = minutes?.rem(60) ?: nowM,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(pickerState) },
        confirmButton = { TextButton(onClick = { onPick(pickerState.hour, pickerState.minute) }) { Text(s.ok) } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onClear) { Text(s.clear) }
                TextButton(onClick = onDismiss) { Text(s.cancel) }
            }
        },
    )
}

// ------------------------------------------------------------------ Pays

/**
 * Champ pays : le drapeau s'affiche dès que le pays est reconnu, et des suggestions (pays déjà utilisés, puis liste
 * des pays) apparaissent sous le champ pendant la saisie.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CountryField(value: String, onChange: (String) -> Unit, known: List<String>) {
    val s = LocalStrings.current
    val q = value.trim()
    val flag = countryFlag(q)
    val fromUsed = known
        .filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
        .map { it to countryFlag(it).orEmpty() }
    val fromTable = countrySuggestions(q, s.languageCode == "fr", 4).filter { !it.first.equals(q, ignoreCase = true) }
    val matches = (fromUsed + fromTable).distinctBy { it.first.lowercase() }.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value, onChange,
            label = { Text(s.country) },
            leadingIcon = flag?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (q.isNotEmpty() && matches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { (name, f) ->
                    FilterChip(selected = false, onClick = { onChange(name) }, label = { Text("$f $name".trim()) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Binôme

private data class BuddySuggestion(val name: String, val phone: String, val email: String, val fromContacts: Boolean)

/**
 * Champ binôme : propose les binômes déjà saisis et, dès la première lettre, les contacts du répertoire (repérés par
 * une icône). La permission d'accès aux contacts n'est demandée qu'à ce moment-là.
 */
@Composable
private fun BuddyField(
    state: AppState,
    contacts: ContactsProvider,
    name: String,
    onName: (String) -> Unit,
    onPick: (name: String, phone: String, email: String, fromContacts: Boolean) -> Unit,
    label: String? = null,
) {
    val s = LocalStrings.current
    var results by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var denied by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) } // nom choisi : plus de suggestions pour ce texte
    val q = name.trim()

    LaunchedEffect(q) {
        if (q.isEmpty() || q == picked || denied) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300) // laisse finir la frappe avant d'interroger le répertoire
        when (val r = contacts.search(q)) {
            is ContactSearch.Found -> results = r.contacts
            ContactSearch.PermissionDenied -> { denied = true; results = emptyList() }
        }
    }

    val active = q.isNotEmpty() && q != picked
    val fromContacts = if (active) results.filter { !it.name.equals(q, ignoreCase = true) } else emptyList()
    val contactKeys = fromContacts.map { it.name.lowercase() }.toSet()
    // Personnes déjà saisies (binômes et palanquées), la plus récente d'abord, sans doublon de nom.
    val previous = if (!active) emptyList() else state.dives.sortedWith(diveOrder)
        .flatMap { d ->
            listOfNotNull(
                d.buddy.takeIf { it.isNotBlank() }?.let { BuddySuggestion(it, d.buddyPhone, d.buddyEmail, d.buddyFromContacts) },
            ) + d.group.filter { it.name.isNotBlank() }.map { BuddySuggestion(it.name, it.phone, it.email, it.fromContacts) }
        }
        .distinctBy { it.name.trim().lowercase() }
        .filter { it.name.contains(q, ignoreCase = true) && !it.name.equals(q, ignoreCase = true) && it.name.trim().lowercase() !in contactKeys }
        .take(4)
    val suggestions = previous + fromContacts.map { BuddySuggestion(it.name, it.phone, it.email, true) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            name, { picked = null; onName(it) },
            label = { Text(label ?: s.buddy) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (denied && q.isNotEmpty()) Text(s.contactsDenied, style = MaterialTheme.typography.bodySmall)
        if (suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column {
                    suggestions.forEach { sug ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { picked = sug.name; onPick(sug.name, sug.phone, sug.email, sug.fromContacts) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Icône seulement pour les contacts du répertoire (pas pour les binômes déjà saisis).
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                if (sug.fromContacts && results.any { it.name == sug.name }) Text("\uD83D\uDC64")
                            }
                            Column {
                                Text(sug.name)
                                val sub = sug.phone.ifBlank { sug.email }
                                if (sub.isNotBlank() && sug.fromContacts) Text(sub, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- Partage

/**
 * Propose d'envoyer la plongée aux personnes données (binôme, palanquée) par SMS, e-mail ou n'importe quelle appli,
 * avec un lien pour l'ajouter à leur carnet en un appui.
 */
@Composable
private fun ShareDiveDialog(
    dive: Dive,
    site: DiveSite,
    actions: PlatformActions,
    recipients: List<Person>,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val message = s.shareMessage(site.fullName(), s.dateText(dive.date), DiveLink.webLink(dive, site))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.shareTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (recipients.size == 1) s.shareIntro(recipients[0].name) else s.shareIntroMany)
                recipients.forEach { person ->
                    val phone = person.phone.filter { it.isDigit() || it == '+' }
                    if (recipients.size > 1) Text(person.name, style = MaterialTheme.typography.titleSmall)
                    if (phone.isNotEmpty()) {
                        Button(onClick = { actions.openLink("sms:$phone?body=${urlEncode(message)}") }, modifier = Modifier.fillMaxWidth()) {
                            Text(s.shareBySms)
                        }
                    }
                    if (person.email.isNotBlank()) {
                        Button(
                            onClick = {
                                actions.openLink("mailto:${person.email.trim()}?subject=${urlEncode(s.shareSubject)}&body=${urlEncode(message)}")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(s.shareByEmail) }
                    }
                }
                OutlinedButton(onClick = { actions.shareText(message) }, modifier = Modifier.fillMaxWidth()) {
                    Text(s.shareOther)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.shareLater) } },
    )
}

/** Plongée reçue d'un binôme : résumé et ajout en un appui. */
@Composable
internal fun SharedDiveDialog(shared: SharedDive, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val siteLine = listOf(shared.site.country, shared.site.name).filter { it.isNotBlank() }.joinToString(" · ")
    val times = listOfNotNull(shared.start, shared.end).joinToString(" – ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.sharedTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.sharedLead)
                Text(siteLine, style = MaterialTheme.typography.titleMedium)
                Text(parseDate(shared.date)?.let(s::formatDate) ?: shared.date)
                if (times.isNotEmpty()) Text(times)
                shared.depth?.let { Text(s.labelled(s.depthLabel, "${formatNum(it)} m")) }
                shared.tempC?.let { Text(s.labelled(s.waterTemp, "${formatNum(it)} °C")) }
            }
        },
        confirmButton = { Button(onClick = onAdd) { Text(s.sharedAdd) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.sharedIgnore) } },
    )
}

/** Secours quand le lien reçu n'est pas cliquable : on colle le message reçu. */
@Composable
private fun PasteSharedDialog(onDismiss: () -> Unit, onShared: (SharedDive) -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.pasteSharedTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it; invalid = false }, label = { Text(s.pasteSharedHint) }, minLines = 3)
                if (invalid) Text(s.sharedInvalid, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val shared = DiveLink.decode(text)
                if (shared == null) invalid = true else onShared(shared)
            }) { Text(s.ok) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
