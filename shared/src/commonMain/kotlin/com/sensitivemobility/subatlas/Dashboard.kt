package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

// Écran d'accueil : un tableau de bord de ce que le carnet contient (nombre de plongées, temps sous l'eau, records, pays,
// plongées par année). Tout est calculé à partir des plongées et des sites déjà enregistrés, rien n'est à saisir.

/** Un pays où l'on a plongé, avec le nombre de plongées qui s'y trouvent. */
internal class CountryStat(val key: String, val label: String, val dives: Int)

/** Nombre de plongées d'une année. */
internal class YearStat(val year: Int, val dives: Int, val byKind: Map<DiveKind?, Int> = emptyMap())

internal class DashboardStats(
    val diveCount: Int,
    /** Somme des durées connues, en minutes. */
    val totalMinutes: Int,
    /** Nombre de plongées dont la durée est connue (début et fin renseignés). */
    val timedDives: Int,
    /** Nombre de sites où l'on a plongé. */
    val siteCount: Int,
    val deepest: Dive?,
    val longest: Dive?,
    val coldest: Dive?,
    val favouriteSite: DiveSite?,
    val favouriteSiteDives: Int,
    /** Pays des sites plongés, du plus fréquenté au moins fréquenté. */
    val countries: List<CountryStat>,
    /** Une entrée par année, de la première plongée à la dernière (les années sans plongée comptent 0). */
    val years: List<YearStat>,
    /** La plongée la plus récente. */
    val last: Dive?,
)

/** Statistiques du carnet. [french] : langue des noms de pays. Les plongées dont le site n'existe plus ne comptent pas dans les sites et pays. */
internal fun computeDashboard(dives: List<Dive>, sites: List<DiveSite>, french: Boolean): DashboardStats {
    val siteById = sites.associateBy { it.id }
    val timed = dives.mapNotNull { it.durationMinutes }

    // Records : la plongée qui détient le maximum (ou le minimum), si au moins une a la donnée.
    val deepest = dives.filter { it.maxDepth != null }.maxByOrNull { it.maxDepth!! }
    val longest = dives.filter { it.durationMinutes != null }.maxByOrNull { it.durationMinutes!! }
    val coldest = dives.filter { it.waterTempC != null }.minByOrNull { it.waterTempC!! }

    val divesPerSite = dives.filter { it.siteId in siteById }.groupingBy { it.siteId }.eachCount()
    val favourite = divesPerSite.maxByOrNull { it.value }

    val countryCounts = LinkedHashMap<String, Pair<String, Int>>() // clé -> (saisie d'origine, nombre)
    for (dive in dives) {
        val country = siteById[dive.siteId]?.country?.takeIf { it.isNotBlank() } ?: continue
        val key = countryKey(country)
        val (sample, count) = countryCounts[key] ?: (country to 0)
        countryCounts[key] = sample to count + 1
    }
    val countries = countryCounts.map { (key, v) -> CountryStat(key, countryLabel(key, french, v.first), v.second) }
        .sortedWith(compareByDescending<CountryStat> { it.dives }.thenBy { it.label.lowercase() })

    val perYear = dives.groupBy { it.date.take(4).toIntOrNull() }.filterKeys { it != null }.mapKeys { it.key!! }
    val years = if (perYear.isEmpty()) emptyList() else (perYear.keys.min()..perYear.keys.max()).map { y ->
        val list = perYear[y].orEmpty()
        YearStat(y, list.size, list.groupingBy { it.kind }.eachCount())
    }

    return DashboardStats(
        diveCount = dives.size,
        totalMinutes = timed.sum(),
        timedDives = timed.size,
        siteCount = divesPerSite.size,
        deepest = deepest,
        longest = longest,
        coldest = coldest,
        favouriteSite = favourite?.let { siteById[it.key] },
        favouriteSiteDives = favourite?.value ?: 0,
        countries = countries,
        years = years,
        last = dives.sortedWith(diveOrder).firstOrNull(),
    )
}

/**
 * Une durée en toutes lettres, en jours, heures et minutes (« 5 jours 10 heures 13 minutes », « 1 heure », « 3 jours 45
 * minutes »), au singulier ou au pluriel selon le nombre, sans unité qui vaudrait zéro. Une durée nulle s'écrit « 0 minute ».
 */
internal fun Strings.spelledDuration(minutes: Int): String {
    val days = minutes / MINUTES_PER_DAY
    val hours = minutes % MINUTES_PER_DAY / 60
    val rest = minutes % 60
    val parts = buildList {
        if (days > 0) add(daysText(days))
        if (hours > 0) add(hoursText(hours))
        if (rest > 0) add(minutesText(rest))
    }
    return parts.joinToString(" ").ifEmpty { minutesText(0) }
}

private const val MINUTES_PER_DAY = 24 * 60

@Composable
internal fun HomeScreen(
    state: AppState,
    pad: PaddingValues,
    scroll: ScrollState,
    onOpenDive: (Long) -> Unit,
    onOpenSite: (Long) -> Unit,
    onAddDive: () -> Unit,
    onShowMap: (List<Long>) -> Unit,
) {
    val s = LocalStrings.current
    val french = s.languageCode == "fr"
    val stats = remember(state.dives.toList(), state.sites.toList(), french) {
        computeDashboard(state.dives.toList(), state.sites.toList(), french)
    }
    Column(
        Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (stats.diveCount == 0) {
            HeroCard {
                Pictogram(Picto.MASK, size = 36.dp, tint = Color.White)
                Text(s.homeEmptyTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(s.homeEmptyText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            }
            Button(onClick = onAddDive, modifier = Modifier.fillMaxWidth()) { Text(s.addDive) }
            return@Column
        }

        HeroCard {
            Text(s.homeDives(stats.diveCount).uppercase(), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Text(stats.diveCount.toString(), fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(s.homeUnderwater(s.spelledDuration(stats.totalMinutes)), style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (stats.timedDives < stats.diveCount) {
                Text(s.homeTimedNote(stats.timedDives, stats.diveCount), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
        }

        // Pastilles de chiffres : deux par ligne, celles qui ont une donnée seulement. Les records ouvrent la plongée.
        val tiles = buildList<@Composable (Modifier) -> Unit> {
            add { m -> StatTile(Picto.PIN, stats.siteCount.toString(), s.homeSitesTile, null, m, null) }
            if (stats.countries.isNotEmpty()) add { m -> StatTile(Picto.GLOBE, stats.countries.size.toString(), s.homeCountriesTile, null, m, null) }
            stats.deepest?.let { d ->
                add { m ->
                    StatTile(Picto.ARROW_DOWN, "${formatNum(d.maxDepth ?: 0.0)} m", s.homeDeepest, state.site(d.siteId)?.name, m) { onOpenDive(d.id) }
                }
            }
            stats.longest?.let { d ->
                add { m ->
                    StatTile(Picto.STOPWATCH, s.spelledDuration(d.durationMinutes ?: 0), s.homeLongest, s.dateText(d.date), m) { onOpenDive(d.id) }
                }
            }
            stats.coldest?.let { d ->
                add { m ->
                    StatTile(Picto.THERMOMETER, "${formatNum(d.waterTempC ?: 0.0)} °C", s.homeColdest, state.site(d.siteId)?.name, m) { onOpenDive(d.id) }
                }
            }
            stats.favouriteSite?.let { site ->
                add { m -> StatTile(Picto.STAR, s.diveCount(stats.favouriteSiteDives), s.homeFavourite, site.name, m) { onOpenSite(site.id) } }
            }
        }
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }

        if (stats.years.size > 1) {
            HomeSection(s.homeByYear)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.padding(16.dp)) { YearBars(stats.years, state.settings) }
            }
        }

        if (stats.countries.isNotEmpty()) {
            HomeSection(s.homeCountries)
            CountryChips(stats.countries)
        }

        stats.last?.let { dive ->
            HomeSection(s.homeLast)
            Card(
                onClick = { onOpenDive(dive.id) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = diveCardColor(dive.kind, state.settings)),
            ) {
                Row(Modifier.height(IntrinsicSize.Min).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.4f)) {
                        Text("#${state.diveNumber(dive.id)} · " + s.dateText(dive.date), style = MaterialTheme.typography.titleMedium)
                        val site = state.site(dive.siteId)
                        if (site != null) SiteLine(site, MaterialTheme.typography.bodyMedium, maxLines = 1) else Text("?")
                        Text(dive.listCompact(s), style = MaterialTheme.typography.bodySmall)
                    }
                    dive.profile?.let { ProfileSparkline(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }

        // Carte des sites où l'on a plongé (ceux qui ont une position).
        val mappable = remember(state.dives.toList(), state.sites.toList()) {
            val dived = state.dives.mapTo(HashSet()) { it.siteId }
            state.sites.filter { it.id in dived && it.latitude != null && it.longitude != null }.map { it.id }
        }
        if (mappable.isNotEmpty()) {
            OutlinedButton(onClick = { onShowMap(mappable) }, modifier = Modifier.fillMaxWidth()) { PictoText(s.homeShowMap, Picto.MAP) }
        }
    }
}

@Composable
private fun HomeSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** Grande carte d'en-tête : dégradé océan, deux vagues translucides en bas, contenu en blanc. */
@Composable
private fun HeroCard(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Ocean, Lagoon)))) {
        Canvas(Modifier.matchParentSize()) {
            for ((level, alpha) in listOf(0.62f to 0.10f, 0.78f to 0.08f)) {
                val base = size.height * level
                val amplitude = 7.dp.toPx()
                val length = 170.dp.toPx()
                val wave = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, base)
                    var x = 0f
                    while (x <= size.width + 6f) {
                        lineTo(x, base + amplitude * sin(x / length * 2f * PI.toFloat() + level * 7f))
                        x += 6f
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(wave, Color.White.copy(alpha = alpha))
            }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** Pastille de chiffre : pictogramme, valeur en gros, libellé et détail ; touchable quand [onClick] est donné. */
@Composable
private fun StatTile(icon: Picto, value: String, label: String, detail: String?, modifier: Modifier, onClick: (() -> Unit)?) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Pictogram(icon, size = 26.dp, tint = MaterialTheme.colorScheme.primary, filled = true)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = modifier, colors = colors, content = content)
    else Card(modifier = modifier, colors = colors, content = content)
}

/** Histogramme des plongées par année ; segments colorés par type de plongée. */
@Composable
private fun YearBars(years: List<YearStat>, settings: Settings) {
    val s = LocalStrings.current
    val neutral = MaterialTheme.colorScheme.outline
    // Couleur d'un type : celle choisie dans les réglages (la même que la carte de la liste), grise si le type n'est pas renseigné.
    fun kindColor(kind: DiveKind?): Color =
        kind?.let { DIVE_PALETTE.getOrNull(settings.colorIndex(it))?.let { c -> Color(c) } } ?: neutral
    val kindsUsed = DiveKind.entries.filter { k -> years.any { (it.byKind[k] ?: 0) > 0 } } +
        (if (years.any { (it.byKind[null] ?: 0) > 0 }) listOf(null) else emptyList())
    val max = years.maxOf { it.dives }.coerceAtLeast(1)
    // Au plus huit étiquettes d'année, et les effectifs au-dessus des barres tant qu'il y a la place.
    val step = (years.size + 7) / 8
    val showCounts = years.size <= 14
    val shortYears = years.size > 8
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        years.forEachIndexed { i, y ->
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Hauteur de ligne fixée : toutes les étiquettes s'alignent, qu'elles soient vides ou non.
                if (showCounts && y.dives > 0) Text(y.dives.toString(), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                if (y.dives > 0) {
                    // Barre empilée : un segment par type de plongée, en proportion (le plus récent en haut).
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height((100.0 * y.dives / max).dp.coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                    ) {
                        for (k in kindsUsed.reversed()) {
                            val n = y.byKind[k] ?: 0
                            if (n > 0) Box(Modifier.fillMaxWidth().weight(n.toFloat()).background(kindColor(k)))
                        }
                    }
                }
                val label = if (i % step == 0 || i == years.lastIndex) {
                    if (shortYears) "’" + (y.year % 100).toString().padStart(2, '0') else y.year.toString()
                } else {
                    " "
                }
                Text(label, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    KindLegend(kindsUsed, ::kindColor, s)
    }
}

/** Pays visités : une pastille par pays, « 🇪🇬 Égypte · 52 ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindLegend(kinds: List<DiveKind?>, color: (DiveKind?) -> Color, s: Strings) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        kinds.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color(k)))
                Text(if (k == null) s.kindNone else s.diveKind(k), Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountryChips(countries: List<CountryStat>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        countries.forEach { c ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("${c.label} · ${c.dives}", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
