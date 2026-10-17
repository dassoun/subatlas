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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.delay
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
internal fun formatNum(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

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
) {
    val s = LocalStrings.current
    var pasting by remember { mutableStateOf(false) }
    val sort = state.settings.diveSort
    Column(Modifier.fillMaxSize().padding(pad)) {
        if (state.dives.isEmpty()) Text(s.noDives, modifier = Modifier.padding(16.dp))
        if (state.dives.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiveSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { state.updateSettings { it.copy(diveSort = option) } },
                        label = { Text(s.sortLabel(option)) },
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.sortedDives(sort), key = { it.id }) { dive ->
                Card(
                    onClick = { onOpen(dive.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.dateText(dive.date), style = MaterialTheme.typography.titleMedium)
                        val diveSite = state.site(dive.siteId)
                        if (diveSite != null) SiteLine(diveSite, MaterialTheme.typography.bodyMedium) else Text("?")
                        Text(dive.listSummary(s), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(s.addDive) }
            TextButton(onClick = { pasting = true }, modifier = Modifier.fillMaxWidth()) { Text(s.pasteSharedButton) }
        }
    }
    if (pasting) PasteSharedDialog(onDismiss = { pasting = false }) { pasting = false; onShared(it) }
}

/** Tri choisi pour la liste ; à égalité, la plus récente d'abord. */
private fun AppState.sortedDives(sort: DiveSort): List<Dive> {
    fun siteName(d: Dive) = site(d.siteId)?.name?.lowercase().orEmpty()
    fun country(d: Dive) = site(d.siteId)?.country?.lowercase().orEmpty()
    return when (sort) {
        DiveSort.DATE_DESC -> dives.sortedWith(diveOrder)
        DiveSort.DATE_ASC -> dives.sortedWith(diveOrder.reversed())
        DiveSort.SITE -> dives.sortedWith(compareBy<Dive> { siteName(it) }.then(diveOrder))
        DiveSort.COUNTRY ->
            dives.sortedWith(compareBy<Dive> { country(it).isEmpty() }.thenBy { country(it) }.thenBy { siteName(it) }.then(diveOrder))
        DiveSort.DEPTH_DESC -> dives.sortedWith(compareByDescending<Dive> { it.maxDepthMeters ?: -1.0 }.then(diveOrder))
        DiveSort.DURATION_DESC -> dives.sortedWith(compareByDescending<Dive> { it.durationMinutes ?: -1 }.then(diveOrder))
        // Les plongées sans SAC calculable passent à la fin.
        DiveSort.SAC_ASC ->
            dives.sortedWith(compareBy<Dive> { it.sacLitersPerMin == null }.thenBy { it.sacLitersPerMin ?: 0.0 }.then(diveOrder))
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
private fun Dive.listSummary(s: Strings): String {
    val volumes = tanks.mapNotNull { it.volumeLiters }.takeIf { it.isNotEmpty() }
        ?.joinToString(" + ") { formatNum(it) }?.let { "$it\u00A0L" }
    return listOfNotNull(
        timeRangeText(s),
        maxDepthMeters?.let { "max\u00A0${formatNum(it)}\u00A0m" },
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
                    d.maxDepthMeters?.let { "max ${formatNum(it)} m" },
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
    val site = state.site(dive.siteId)
    val canShare = site != null && dive.buddy.isNotBlank() && (dive.buddyPhone.isNotBlank() || dive.buddyEmail.isNotBlank())
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
        dive.timeRangeText(s)?.let { Text(it) }
        dive.durationMinutes?.let { Text(s.labelled(s.durationLabel, s.minutesShort(it))) }
        dive.maxDepthMeters?.let { Text(s.labelled(s.depthLabel, "${formatNum(it)} m")) }
        dive.avgDepthMeters?.let { Text(s.labelled(s.avgDepthLabel, "${formatNum(it)} m")) }

        dive.tanks.forEachIndexed { i, tank ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${s.tankTitle(i + 1)} · ${tank.gasLabel(s)}", style = MaterialTheme.typography.titleSmall)
                    tank.volumeLiters?.let { Text(s.labelled(s.volume, "${formatNum(it)} L")) }
                    tank.pressureText()?.let { Text(it) }
                    tank.maxDepthMeters?.let { Text(s.labelled(s.depthLabel, "${formatNum(it)} m")) }
                    tank.consumedLiters?.let { Text(s.consumedLine(round(it).toInt())) }
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
                Text(s.shareButton(dive.buddy))
            }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(s.edit) }
        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(s.delete) }
    }
    if (sharing && site != null) ShareDiveDialog(dive, site, actions) { sharing = false }
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
    val depth: String = "",
) {
    private val o2Val get() = o2.trim().toIntOrNull()
    private val heVal get() = he.trim().toIntOrNull()

    val volumeOk get() = volume.isBlank() || volume.toDec() != null
    val o2Ok get() = o2Val?.let { it in 1..100 } == true
    val heOk get() = heVal?.let { it in 0..99 && it + (o2Val ?: 0) <= 100 } == true
    val pStartOk get() = pStart.isBlank() || pStart.trim().toIntOrNull() != null
    val pEndOk get() = pEnd.isBlank() || pEnd.trim().toIntOrNull() != null
    val depthOk get() = depth.isBlank() || depth.toDec() != null
    val valid get() = volumeOk && o2Ok && heOk && pStartOk && pEndOk && depthOk

    fun toTank() = Tank(
        volumeLiters = volume.toDec(),
        o2Percent = o2Val ?: 21,
        hePercent = heVal ?: 0,
        pressureStartBar = pStart.trim().toIntOrNull(),
        pressureEndBar = pEnd.trim().toIntOrNull(),
        maxDepthMeters = depth.toDec(),
    )
}

private fun Tank.toForm() = TankForm(
    volume = volumeLiters?.let(::formatNum) ?: "",
    o2 = o2Percent.toString(),
    he = hePercent.toString(),
    pStart = pressureStartBar?.toString() ?: "",
    pEnd = pressureEndBar?.toString() ?: "",
    depth = maxDepthMeters?.let(::formatNum) ?: "",
)

private fun String.toDec(): Double? = trim().replace(',', '.').toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class)
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
    var avgDepth by remember { mutableStateOf(existing?.avgDepthMeters?.let(::formatNum) ?: "") }
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

    val extrasOk = (avgDepth.isBlank() || avgDepth.toDec() != null) &&
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
        NumField(s.avgDepthLabel, avgDepth, { avgDepth = it }, Modifier.fillMaxWidth(), error = avgDepth.isNotBlank() && avgDepth.toDec() == null, unit = "m")
        Text(s.avgDepthHint, style = MaterialTheme.typography.bodySmall)

        // --- Bouteilles
        Text(s.tanksSection, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        tanks.forEachIndexed { i, t ->
            TankEditor(
                number = i + 1,
                tank = t,
                canRemove = tanks.size > 1,
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
                    )
                state.saveDive(dive)
                if (shareAfter && buddyFromContacts) sharedDive = dive else onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.save) }
    }

    sharedDive?.let { d ->
        val diveSite = state.site(d.siteId)
        if (diveSite == null) onDone() else ShareDiveDialog(d, diveSite, actions) { sharedDive = null; onDone() }
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
private fun TankEditor(number: Int, tank: TankForm, canRemove: Boolean, onChange: (TankForm) -> Unit, onRemove: () -> Unit) {
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
                NumField(s.depthLabel, tank.depth, { onChange(tank.copy(depth = it)) }, Modifier.weight(1f), error = !tank.depthOk, unit = "m")
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
    val previous = if (!active) emptyList() else state.dives.sortedWith(diveOrder)
        .filter { it.buddy.isNotBlank() }
        .distinctBy { it.buddy.trim().lowercase() }
        .filter { it.buddy.contains(q, ignoreCase = true) && !it.buddy.equals(q, ignoreCase = true) && it.buddy.trim().lowercase() !in contactKeys }
        .take(4)
    val suggestions = previous.map { BuddySuggestion(it.buddy, it.buddyPhone, it.buddyEmail, it.buddyFromContacts) } +
        fromContacts.map { BuddySuggestion(it.name, it.phone, it.email, true) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            name, { picked = null; onName(it) },
            label = { Text(s.buddy) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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

/** Propose d'envoyer la plongée au binôme (SMS, e-mail ou n'importe quelle appli) avec un lien d'ajout en un appui. */
@Composable
private fun ShareDiveDialog(dive: Dive, site: DiveSite, actions: PlatformActions, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val message = s.shareMessage(site.fullName(), s.dateText(dive.date), DiveLink.encode(dive, site), APP_DOWNLOAD_URL)
    val phone = dive.buddyPhone.filter { it.isDigit() || it == '+' }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.shareTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.shareIntro(dive.buddy))
                if (phone.isNotEmpty()) {
                    Button(onClick = { actions.openLink("sms:$phone?body=${urlEncode(message)}") }, modifier = Modifier.fillMaxWidth()) {
                        Text(s.shareBySms)
                    }
                }
                if (dive.buddyEmail.isNotBlank()) {
                    Button(
                        onClick = {
                            actions.openLink("mailto:${dive.buddyEmail.trim()}?subject=${urlEncode(s.shareSubject)}&body=${urlEncode(message)}")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.shareByEmail) }
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
