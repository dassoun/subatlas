package com.sensitivemobility.subatlas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Durée d'affichage de l'écran de démarrage de l'appli, avant le fondu vers le contenu. */
private const val SPLASH_DURATION_MS = 1500L

private sealed interface Screen {
    data object Dives : Screen
    data object Sites : Screen
    data object Centers : Screen
    data object Checklists : Screen
    data object Safety : Screen
    data class SafetyDetail(val id: String) : Screen
    data object Settings : Screen
    data object SiteDuplicates : Screen
    data class DiveDetail(val id: Long) : Screen
    data class DiveForm(val id: Long?, val siteId: Long?) : Screen
    data class SiteDetail(val id: Long) : Screen
    data class SiteForm(val id: Long?) : Screen
    data class ChecklistDetail(val id: Long) : Screen
    data class CenterDetail(val id: Long) : Screen
    data class CenterForm(val id: Long?) : Screen
}

@Composable
fun App(storage: Storage, location: LocationProvider, actions: PlatformActions, contacts: ContactsProvider) {
    // rememberSaveable : après une rotation, l'écran de démarrage ne réapparaît pas.
    var splashVisible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(SPLASH_DURATION_MS)
        splashVisible = false
    }

    Box(Modifier.fillMaxSize()) {
        AppContent(storage, location, actions, contacts)
        AnimatedVisibility(visible = splashVisible, enter = EnterTransition.None, exit = fadeOut(tween(400))) {
            SplashScreen()
        }
    }
}

@Composable
private fun AppContent(storage: Storage, location: LocationProvider, actions: PlatformActions, contacts: ContactsProvider) {
    val systemLanguage = remember { actions.systemLanguage() }
    val state = remember { AppState(storage, systemLanguage) }
    val strings = stringsFor(state.settings.language, systemLanguage)
    val stack = remember { mutableStateListOf<Screen>(Screen.Dives) }
    // Position de défilement de chaque liste, conservée quand on ouvre une fiche puis qu'on revient.
    val divesListState = rememberLazyListState()
    val sitesListState = rememberLazyListState()
    // Recherche de la liste des sites, conservée en ouvrant une fiche puis en revenant.
    var sitesQuery by remember { mutableStateOf("") }
    val centersListState = rememberLazyListState()
    val checklistsListState = rememberLazyListState()
    val current = stack.last()

    fun push(s: Screen) { stack.add(s) }
    /** Remplace la fiche affichée (passage à la fiche voisine) : « retour » ramène toujours à la liste. */
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun goTab(s: Screen) { stack.clear(); stack.add(s) }

    BackHandler(enabled = stack.size > 1) { pop() }

    val scope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<ImportData?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sharedIncoming by remember { mutableStateOf<SharedDive?>(null) }
    var incomingPlan by remember { mutableStateOf<List<ShearwaterPlanItem>?>(null) }
    var incomingDiveFile by remember { mutableStateOf<DiveFile?>(null) }
    var incomingSkipped by remember { mutableStateOf(0) }
    var incomingSiteMatches by remember { mutableStateOf<List<Pair<String, DiveSite>>>(emptyList()) }
    var incomingAttach by remember { mutableStateOf(true) }
    var incomingReading by remember { mutableStateOf(false) }

    // Lien de plongée ouvert depuis un message (ou collé) : on propose de l'ajouter au carnet.
    val incoming = IncomingLink.pending
    LaunchedEffect(incoming) {
        if (incoming != null) {
            val decoded = DiveLink.decode(incoming)
            IncomingLink.pending = null
            if (decoded != null) sharedIncoming = decoded else notice = strings.sharedInvalid
        }
    }

    // Export Shearwater reçu par « Partager > SubAtlas » ou « Ouvrir avec » : on prépare l'import et on demande confirmation.
    val incomingFile = IncomingFile.pending
    LaunchedEffect(incomingFile) {
        if (incomingFile != null) {
            // Remettre `pending` à vide change la clé de cet effet et l'annulerait en plein décodage : le travail
            // tourne donc dans le scope de la composition, que ce changement de clé ne touche pas.
            IncomingFile.pending = null
            scope.launch {
                // Un fichier de plongée envoyé par un binôme (JSON) : on l'ajoute après confirmation.
                val diveFile = withContext(Dispatchers.Default) { DiveFileCodec.decode(incomingFile) }
                if (diveFile != null) {
                    incomingDiveFile = diveFile
                    return@launch
                }
                try {
                    incomingReading = true
                    val file = try {
                        withContext(Dispatchers.Default) { ShearwaterImport.parse(incomingFile) }
                    } finally {
                        incomingReading = false
                    }
                    if (file.dives.isEmpty()) {
                        notice = strings.shearwaterNone
                    } else {
                        incomingSkipped = file.skipped
                        val newPlan = state.planShearwaterImport(file.dives)
                        incomingSiteMatches = state.similarSiteMatches(newPlan)
                        incomingAttach = true
                        incomingPlan = newPlan
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ShearwaterImport.NotShearwaterException) {
                    notice = strings.shearwaterInvalid
                } catch (e: Exception) {
                    notice = strings.importFailed(e.message ?: strings.unknownError)
                }
            }
        }
    }

    fun startExport() = scope.launch {
        val name = "subatlas-" + Clock.System.now().toString().take(10) + ".json"
        notice = try {
            if (actions.saveTextFile(name, state.exportJson())) strings.dataExported else null
        } catch (e: Exception) {
            strings.exportFailed(e.message ?: strings.unknownError)
        }
    }

    fun startImport() = scope.launch {
        try {
            val text = actions.openTextFile() ?: return@launch
            pendingImport = BackupCodec.decode(text)
        } catch (e: BackupException) {
            notice = strings.backupError(e)
        } catch (e: Exception) {
            notice = strings.importFailed(e.message ?: strings.unknownError)
        }
    }

    SubAtlasTheme(
        state.settings.theme, strings, state.settings.ascent, state.settings.chartSeries,
        onToggleSeries = { series ->
            state.updateSettings { it.copy(chartSeries = if (series in it.chartSeries) it.chartSeries - series else it.chartSeries + series) }
        },
    ) {
        when (current) {
            Screen.Dives, Screen.Sites, Screen.Centers, Screen.Checklists, Screen.Safety, Screen.Settings -> Scaffold(
                topBar = {
                    Bar(
                        when (current) {
                            Screen.Dives -> if (state.dives.isEmpty()) strings.divesTitle else "${strings.divesTitle} (${state.dives.size})"
                            Screen.Sites -> strings.sitesTitle
                            Screen.Centers -> strings.centersTitle
                            Screen.Checklists -> strings.checklistsTitle
                            Screen.Safety -> strings.safetyTitle
                            else -> strings.settingsTitle
                        },
                        null,
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = current == Screen.Dives,
                            onClick = { goTab(Screen.Dives) },
                            icon = { Text("🤿") },
                            label = { TabLabel(strings.tabDives) },
                            alwaysShowLabel = false,
                        )
                        NavigationBarItem(
                            selected = current == Screen.Sites,
                            onClick = { goTab(Screen.Sites) },
                            icon = { Text("📍") },
                            label = { TabLabel(strings.tabSites) },
                            alwaysShowLabel = false,
                        )
                        NavigationBarItem(
                            selected = current == Screen.Centers,
                            onClick = { goTab(Screen.Centers) },
                            icon = { Text("⚓") },
                            label = { TabLabel(strings.tabCenters) },
                            alwaysShowLabel = false,
                        )
                        NavigationBarItem(
                            selected = current == Screen.Checklists,
                            onClick = { goTab(Screen.Checklists) },
                            icon = { Text("✅") },
                            label = { TabLabel(strings.tabChecklists) },
                            alwaysShowLabel = false,
                        )
                        NavigationBarItem(
                            selected = current == Screen.Safety,
                            onClick = { goTab(Screen.Safety) },
                            icon = { Text("🛟") },
                            label = { TabLabel(strings.tabSafety) },
                            alwaysShowLabel = false,
                        )
                        NavigationBarItem(
                            selected = current == Screen.Settings,
                            onClick = { goTab(Screen.Settings) },
                            icon = { Text("⚙️") },
                            label = { TabLabel(strings.tabSettings) },
                            alwaysShowLabel = false,
                        )
                    }
                },
            ) { pad ->
                when (current) {
                    Screen.Dives ->
                        DiveList(
                            state, pad,
                            onOpen = { push(Screen.DiveDetail(it)) },
                            onAdd = { push(Screen.DiveForm(null, null)) },
                            onShared = { sharedIncoming = it },
                            actions = actions,
                            listState = divesListState,
                        )
                    Screen.Sites ->
                        SiteList(state, pad, onOpen = { push(Screen.SiteDetail(it)) }, onAdd = { push(Screen.SiteForm(null)) }, onDuplicates = { push(Screen.SiteDuplicates) }, listState = sitesListState, query = sitesQuery, onQuery = { sitesQuery = it })
                    Screen.Centers ->
                        CenterList(state, pad, onOpen = { push(Screen.CenterDetail(it)) }, onAdd = { push(Screen.CenterForm(null)) }, listState = centersListState)
                    Screen.Checklists -> ChecklistList(state, pad, onOpen = { push(Screen.ChecklistDetail(it)) }, listState = checklistsListState)
                    Screen.Safety -> SafetyList(pad, actions, onOpen = { push(Screen.SafetyDetail(it)) })
                    else -> SettingsScreen(state, pad, onExport = ::startExport, onImport = ::startImport)
                }
            }

            is Screen.DiveDetail -> {
                val dive = state.dive(current.id)
                val diveSite = dive?.let { state.site(it.siteId) }
                val diveIds = state.dives.sortedWith(diveOrder).map { it.id } // plus récente d'abord
                val di = diveIds.indexOf(current.id)
                // Glissé à droite : plongée suivante (plus récente) ; à gauche : précédente (plus ancienne).
                if (dive == null) pop() else Scaffold(
                    modifier = Modifier.swipeNeighbours(
                        onRight = diveIds.getOrNull(di - 1)?.let { { replaceTop(Screen.DiveDetail(it)) } },
                        onLeft = diveIds.getOrNull(di + 1)?.let { { replaceTop(Screen.DiveDetail(it)) } },
                    ),
                    topBar = { Bar(strings.dateText(dive.date), ::pop, leading = "#${state.diveNumber(dive.id)}", trailing = dive.startTime) },
                ) { pad ->
                    DiveDetailView(
                        dive, state, pad, actions,
                        onEdit = { push(Screen.DiveForm(dive.id, null)) },
                        onDelete = { state.deleteDive(dive.id); pop() },
                        onOpenSite = { push(Screen.SiteDetail(it)) },
                        onOpenProcedure = { push(Screen.SafetyDetail(SAFETY_FAST_ASCENT)) },
                    )
                }
            }

            is Screen.SafetyDetail -> {
                val card = strings.safetyCards.firstOrNull { it.id == current.id }
                if (card == null) pop() else Scaffold(topBar = { Bar(card.title, ::pop) }) { pad ->
                    SafetyDetail(card, pad, actions)
                }
            }

            is Screen.DiveForm -> {
                // Comme en consultation : glissé à droite = plongée suivante, à gauche = précédente (modification d'une plongée existante).
                val diveIds = state.dives.sortedWith(diveOrder).map { it.id }
                val di = current.id?.let { diveIds.indexOf(it) } ?: -1
                // Demande de changement de plongée : le formulaire propose d'enregistrer avant d'y aller s'il a été modifié.
                var formGo by remember { mutableStateOf<Long?>(null) }
                fun goTo(id: Long) {
                    // Le retour revient à la fiche de la plongée affichée, pas à celle d'où l'on est parti.
                    val below = stack.lastIndex - 1
                    if (below >= 0 && stack[below] is Screen.DiveDetail) stack[below] = Screen.DiveDetail(id)
                    replaceTop(Screen.DiveForm(id, null))
                }
                Scaffold(
                    modifier = if (di < 0) Modifier else Modifier.swipeNeighbours(
                        onRight = diveIds.getOrNull(di - 1)?.let { { formGo = it } },
                        onLeft = diveIds.getOrNull(di + 1)?.let { { formGo = it } },
                    ),
                    topBar = { Bar(if (current.id == null) strings.newDive else "${strings.editDive} · #${state.diveNumber(current.id)}", ::pop) },
                ) { pad ->
                    // La clé repart d'un formulaire neuf pour chaque plongée.
                    key(current.id) {
                        DiveForm(
                            state, current.id?.let(state::dive), current.siteId, location, actions, contacts, pad,
                            goRequest = formGo, onGo = ::goTo, onGoHandled = { formGo = null },
                        ) { pop() }
                    }
                }
            }

            is Screen.SiteDetail -> {
                val site = state.site(current.id)
                val siteIds = state.sites.sortedBy { it.name.lowercase() }.map { it.id }
                val si = siteIds.indexOf(current.id)
                var mergePick by remember(current.id) { mutableStateOf(false) }
                var mergeWith by remember(current.id) { mutableStateOf<DiveSite?>(null) }
                if (site == null) pop() else Scaffold(
                    modifier = Modifier.swipeNeighbours(
                        onRight = siteIds.getOrNull(si + 1)?.let { { replaceTop(Screen.SiteDetail(it)) } },
                        onLeft = siteIds.getOrNull(si - 1)?.let { { replaceTop(Screen.SiteDetail(it)) } },
                    ),
                    topBar = { Bar(site.name, ::pop) },
                ) { pad ->
                    SiteDetailView(
                        site, state.divesAt(site.id), pad, actions,
                        onEdit = { push(Screen.SiteForm(site.id)) },
                        onDelete = { state.deleteSite(site.id); pop() },
                        onMerge = { mergePick = true },
                        onAddDive = { push(Screen.DiveForm(null, site.id)) },
                        onOpenDive = { push(Screen.DiveDetail(it)) },
                    )
                    if (mergePick) {
                        PickSiteToMergeDialog(state, site, onPick = { mergePick = false; mergeWith = it }, onDismiss = { mergePick = false })
                    }
                    mergeWith?.let { other ->
                        ConfirmMergeDialog(
                            state, site, listOf(other), onExport = { startExport() },
                            onConfirm = { state.mergeSites(site.id, setOf(other.id)); mergeWith = null },
                            onDismiss = { mergeWith = null },
                        )
                    }
                }
            }

            Screen.SiteDuplicates -> Scaffold(topBar = { Bar(strings.duplicatesTitle, ::pop) }) { pad ->
                SiteDuplicatesScreen(state, pad, onExport = { startExport() })
            }

            is Screen.SiteForm -> Scaffold(
                topBar = { Bar(if (current.id == null) strings.newSite else strings.editSite, ::pop) },
            ) { pad ->
                SiteForm(state, current.id?.let(state::site), location, actions, pad) { pop() }
            }

            is Screen.CenterDetail -> {
                val center = state.center(current.id)
                val centerIds = state.centers.sortedBy { it.name.lowercase() }.map { it.id }
                val ci = centerIds.indexOf(current.id)
                if (center == null) pop() else Scaffold(
                    modifier = Modifier.swipeNeighbours(
                        onRight = centerIds.getOrNull(ci + 1)?.let { { replaceTop(Screen.CenterDetail(it)) } },
                        onLeft = centerIds.getOrNull(ci - 1)?.let { { replaceTop(Screen.CenterDetail(it)) } },
                    ),
                    topBar = { Bar(center.name, ::pop) },
                ) { pad ->
                    CenterDetailView(
                        center, pad, actions,
                        onEdit = { push(Screen.CenterForm(center.id)) },
                        onDelete = { state.deleteCenter(center.id); pop() },
                    )
                }
            }

            is Screen.CenterForm -> Scaffold(
                topBar = { Bar(if (current.id == null) strings.newCenter else strings.editCenter, ::pop) },
            ) { pad ->
                CenterForm(state, current.id?.let(state::center), location, pad) { pop() }
            }

            is Screen.ChecklistDetail -> {
                val list = state.checklist(current.id)
                if (list == null) pop() else Scaffold(topBar = { Bar(list.title, ::pop) }) { pad ->
                    ChecklistView(state, list, pad, onDeleted = ::pop)
                }
            }
        }

        if (incomingReading) ReadingDialog()
        incomingPlan?.let { items ->
            ShearwaterPlanDialog(
                items,
                incomingSkipped,
                siteMatches = incomingSiteMatches,
                attachSimilar = incomingAttach,
                onAttachChange = { incomingAttach = it },
                onConfirm = {
                    val (created, merged) = state.applyShearwaterImport(items, incomingAttach)
                    incomingPlan = null
                    goTab(Screen.Dives)
                    notice = strings.shearwaterDone(created, merged)
                },
                onDismiss = { incomingPlan = null },
            )
        }
        incomingDiveFile?.let { file ->
            SharedDiveDialog(
                file.summary(),
                onAdd = {
                    val id = state.addDiveFile(file)
                    incomingDiveFile = null
                    push(Screen.DiveDetail(id))
                },
                onDismiss = { incomingDiveFile = null },
            )
        }
        sharedIncoming?.let { shared ->
            SharedDiveDialog(
                shared,
                onAdd = {
                    val id = state.addSharedDive(shared)
                    sharedIncoming = null
                    push(Screen.DiveDetail(id))
                },
                onDismiss = { sharedIncoming = null },
            )
        }
        pendingImport?.let { data ->
            ImportDialog(
                data,
                onMerge = { state.importData(data, replace = false); pendingImport = null; notice = strings.dataImported },
                onReplace = { state.importData(data, replace = true); pendingImport = null; notice = strings.dataReplaced },
                onDismiss = { pendingImport = null },
            )
        }
        notice?.let { text ->
            AlertDialog(
                onDismissRequest = { notice = null },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { notice = null }) { Text(strings.ok) } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, leading: String? = null, trailing: String? = null) {
    TopAppBar(
        title = {
            if (leading == null && trailing == null) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                // Trois zones : un texte à gauche, le titre au centre, un texte à droite (numéro · date · heure de début).
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(leading.orEmpty(), Modifier.weight(1f), maxLines = 1)
                    Text(title, maxLines = 1)
                    Text(trailing.orEmpty(), Modifier.weight(1f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        },
        navigationIcon = { if (onBack != null) BackArrow(onBack) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            titleContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    )
}

@Composable
private fun ImportDialog(data: ImportData, onMerge: () -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.importData) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.importContains(data.sites.size, data.centers?.size, data.dives?.size, data.checklists.size))
                if (data.skipped > 0) Text(s.importSkipped(data.skipped), color = StatusWarn)
                Text(s.importMergeHelp)
                TextButton(onClick = onReplace) {
                    Text(s.importReplace, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onMerge) { Text(s.importMerge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

// -------------------------------------------------------------- Réglages

@Composable
private fun SettingsScreen(state: AppState, pad: PaddingValues, onExport: () -> Unit, onImport: () -> Unit) {
    val s = LocalStrings.current
    val scroll = rememberScrollState()
    var advanced by remember { mutableStateOf(false) }
    var devMode by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    // Au déverrouillage, la page suit l'ouverture animée de la zone développeur jusqu'en bas.
    LaunchedEffect(devMode) {
        if (devMode) {
            val start = withFrameNanos { it }
            do {
                val t = withFrameNanos { it }
                scroll.scrollTo(scroll.maxValue)
            } while (t - start < 600_000_000L)
        }
    }
    // En dépliant « Avancé », la page défile en douceur jusqu'au nouveau contenu.
    LaunchedEffect(advanced) {
        if (advanced) {
            withFrameNanos { } // laisse le contenu se mesurer pour que le défilement connaisse la nouvelle fin
            scroll.animateScrollTo(scroll.maxValue, tween(450))
        }
    }
    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(s.themeSection)
        ChoiceChips(
            ThemeMode.entries, state.settings.theme,
            label = {
                when (it) {
                    ThemeMode.SYSTEM -> s.themeSystem
                    ThemeMode.LIGHT -> s.themeLight
                    ThemeMode.DARK -> s.themeDark
                }
            },
        ) { choice -> state.updateSettings { it.copy(theme = choice) } }

        SectionTitle(s.languageSection)
        ChoiceChips(
            AppLanguage.entries, state.settings.language,
            // Les noms des langues restent dans leur propre langue.
            label = {
                when (it) {
                    AppLanguage.SYSTEM -> s.languageSystem
                    AppLanguage.FR -> "Français"
                    AppLanguage.EN -> "English"
                }
            },
        ) { choice -> state.updateSettings { it.copy(language = choice) } }

        SectionTitle(s.kindColorsSection)
        Text(s.kindColorsHint, style = MaterialTheme.typography.bodyMedium)
        DiveKind.entries.forEach { kind ->
            Text(s.diveKind(kind), style = MaterialTheme.typography.bodyLarge)
            ColorChoice(DIVE_PALETTE, selected = state.settings.colorIndex(kind), noneLabel = s.colorNone) { index ->
                state.updateSettings { it.copy(kindColors = it.kindColors + (kind.name to index)) }
            }
        }

        SectionTitle(s.ascentSection)
        Text(s.ascentHint, style = MaterialTheme.typography.bodyMedium)
        val ascent = state.settings.ascent
        fun set(transform: (AscentScale) -> AscentScale) = state.updateSettings { it.copy(ascent = transform(it.ascent)) }
        Text(s.ascentLevel(1), style = MaterialTheme.typography.bodyLarge)
        Stepper(s.ascentLimit(ascent.yellowAbove), { set { a -> a.copy(yellowAbove = (a.yellowAbove - 1).coerceAtLeast(1)) } },
            { set { a -> a.copy(yellowAbove = (a.yellowAbove + 1).coerceAtMost(a.orangeAbove - 1)) } })
        ColorChoice(ASCENT_PALETTE, ascent.yellowColor, null) { i -> set { a -> a.copy(yellowColor = i) } }
        Text(s.ascentLevel(2), style = MaterialTheme.typography.bodyLarge)
        Stepper(s.ascentLimit(ascent.orangeAbove), { set { a -> a.copy(orangeAbove = (a.orangeAbove - 1).coerceAtLeast(a.yellowAbove + 1)) } },
            { set { a -> a.copy(orangeAbove = (a.orangeAbove + 1).coerceAtMost(a.redAbove - 1)) } })
        ColorChoice(ASCENT_PALETTE, ascent.orangeColor, null) { i -> set { a -> a.copy(orangeColor = i) } }
        Text(s.ascentLevel(3), style = MaterialTheme.typography.bodyLarge)
        Stepper(s.ascentLimit(ascent.redAbove), { set { a -> a.copy(redAbove = (a.redAbove - 1).coerceAtLeast(a.orangeAbove + 1)) } },
            { set { a -> a.copy(redAbove = (a.redAbove + 1).coerceAtMost(99)) } })
        ColorChoice(ASCENT_PALETTE, ascent.redColor, null) { i -> set { a -> a.copy(redColor = i) } }
        TextButton(onClick = { set { AscentScale() } }) { Text(s.ascentReset) }

        SectionTitle(s.dataSection)
        Text(s.dataHint, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text(s.exportData) }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(s.importData) }

        // Section repliée par défaut : les actions dangereuses ne sont pas à portée d'un appui distrait.
        var confirmDelete by remember { mutableStateOf(false) }
        // Nombre de plongées effacées, affiché une fois l'opération terminée.
        var deletedCount by remember { mutableStateOf<Int?>(null) }
        var confirmNoGps by remember { mutableStateOf(false) }
        var deletedSites by remember { mutableStateOf<Int?>(null) }
        Row(
            Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { SectionTitle(s.advancedSection) }
            Text(if (advanced) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (advanced) {
            // 7 appuis rapprochés sur ce libellé : déverrouille les fonctions réservées au développement.
            Text(
                s.deleteAllDivesHint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    taps = if (now - lastTap < 600) taps + 1 else 1
                    lastTap = now
                    if (taps >= 7 && !devMode) devMode = true
                },
            )
            Button(
                onClick = { confirmDelete = true },
                enabled = state.dives.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("⚠️ " + s.deleteAllDives) }
            AnimatedVisibility(
                visible = devMode,
                enter = androidx.compose.animation.expandVertically(tween(450)) + androidx.compose.animation.fadeIn(tween(450)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(s.devSection)
                    Text(s.deleteNoGpsHint, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { confirmNoGps = true },
                        enabled = state.sites.any { it.latitude == null || it.longitude == null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("⚠️ " + s.deleteNoGps) }
                }
            }
        }
        if (confirmNoGps) {
            val doomed = state.sites.filter { it.latitude == null || it.longitude == null }.map { it.id }.toSet()
            val diveCount = state.dives.count { it.siteId in doomed }
            AlertDialog(
                onDismissRequest = { confirmNoGps = false },
                icon = { Text("⚠️", fontSize = 32.sp) },
                title = { Text(s.deleteNoGpsTitle, color = MaterialTheme.colorScheme.error) },
                text = { Text(s.deleteNoGpsMessage(doomed.size, diveCount)) },
                confirmButton = {
                    Button(
                        onClick = { deletedSites = state.deleteSitesWithoutGps(); confirmNoGps = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(s.deleteAllConfirm) }
                },
                dismissButton = { TextButton(onClick = { confirmNoGps = false }) { Text(s.cancel) } },
            )
        }
        deletedSites?.let { n ->
            AlertDialog(
                onDismissRequest = { deletedSites = null },
                icon = { Text("✅", fontSize = 32.sp) },
                title = { Text(s.deleteNoGpsDoneTitle) },
                text = { Text(s.deleteNoGpsDone(n)) },
                confirmButton = { TextButton(onClick = { deletedSites = null }) { Text(s.ok) } },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                icon = { Text("⚠️", fontSize = 32.sp) },
                title = { Text(s.deleteAllTitle, color = MaterialTheme.colorScheme.error) },
                text = { Text(s.deleteAllMessage(state.dives.size)) },
                confirmButton = {
                    Button(
                        onClick = { deletedCount = state.dives.size; state.deleteAllDives(); confirmDelete = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(s.deleteAllConfirm) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel) } },
            )
        }
        deletedCount?.let { n ->
            AlertDialog(
                onDismissRequest = { deletedCount = null },
                icon = { Text("\u2705", fontSize = 32.sp) },
                title = { Text(s.deleteAllDoneTitle) },
                text = { Text(s.deleteAllDone(n)) },
                confirmButton = { TextButton(onClick = { deletedCount = null }) { Text(s.ok) } },
            )
        }
    }
}

/** Valeur réglable par pas de un : un bouton « − », la valeur, un bouton « + ». */
@Composable
private fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 130.dp))
        OutlinedButton(onClick = onPlus, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}

/** Rangée de pastilles d'une [palette] ; avec [noneLabel], la première (barrée) retire la couleur. */
@Composable
private fun ColorChoice(palette: List<Long>, selected: Int, noneLabel: String?, onSelect: (Int) -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ((if (noneLabel != null) listOf(-1) else emptyList()) + palette.indices).forEach { index ->
            val color = palette.getOrNull(index)?.let { Color(it) }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (index == selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
                    .clickable { onSelect(index) }
                    .semantics { if (color == null && noneLabel != null) contentDescription = noneLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (color == null) {
                    Canvas(Modifier.size(18.dp)) {
                        drawLine(ring, Offset(size.width, 0f), Offset(0f, size.height), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/** Libellé d'un onglet : sur une seule ligne et un peu plus petit, seul l'onglet actif affiche son libellé. */
@Composable
private fun TabLabel(text: String) {
    Text(text, fontSize = 11.sp, maxLines = 1, softWrap = false)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun BackArrow(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.inverseOnSurface
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 6.dp, end = 10.dp).size(40.dp).background(tint.copy(alpha = 0.18f), CircleShape),
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 2.5.dp.toPx()
            drawLine(tint, Offset(w * 0.92f, h / 2), Offset(w * 0.1f, h / 2), stroke, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.48f, h * 0.1f), Offset(w * 0.1f, h / 2), stroke, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.48f, h * 0.9f), Offset(w * 0.1f, h / 2), stroke, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- Sites

@Composable
private fun SiteList(state: AppState, pad: PaddingValues, onOpen: (Long) -> Unit, onAdd: () -> Unit, onDuplicates: () -> Unit, listState: LazyListState, query: String, onQuery: (String) -> Unit) {
    val s = LocalStrings.current
    val duplicateCount = remember(state.sites.toList(), state.settings.distinctSites) { state.siteDuplicateGroups().size }
    // Recherche sur le nom et le pays, sans tenir compte des accents ni de la casse.
    val needle = normalizeSiteName(query)
    val shown = if (needle.isEmpty()) state.sites.toList() else state.sites.filter {
        normalizeSiteName(it.name + " " + it.countryText()).contains(needle)
    }
    Column(Modifier.fillMaxSize().padding(pad)) {
        if (state.sites.isEmpty()) {
            Text(
                s.noSites,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            OutlinedTextField(
                query, onQuery,
                placeholder = { Text(s.searchSites) },
                singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("✕") } },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            if (shown.isEmpty()) Text(s.noSiteFound, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { site ->
                Card(
                    onClick = { onOpen(site.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (site.country.isNotBlank()) {
                            Text(site.countryText(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        SiteLine(DiveSite(id = site.id, name = site.name, type = site.type), MaterialTheme.typography.titleMedium)
                        val la = site.latitude
                        val lo = site.longitude
                        Text(
                            if (la != null && lo != null) formatCoords(la, lo) else s.positionNotSet,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val diveCount = state.dives.count { it.siteId == site.id }
                        Text(
                            listOfNotNull(
                                s.access(site.access),
                                site.maxDepthMeters?.let { s.maxDepthShort(it) },
                                if (diveCount > 0) s.diveCount(diveCount) else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (duplicateCount > 0) {
            OutlinedButton(onClick = onDuplicates, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Text(s.duplicatesButton(duplicateCount))
            }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(s.addSite) }
    }
}

@Composable
private fun SiteDetailView(
    site: DiveSite,
    dives: List<Dive>,
    pad: PaddingValues,
    actions: PlatformActions,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit,
    onAddDive: () -> Unit,
    onOpenDive: (Long) -> Unit,
) {
    val s = LocalStrings.current
    var confirm by remember { mutableStateOf(false) }
    val lat = site.latitude
    val lon = site.longitude
    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (site.country.isNotBlank()) {
            Text(site.countryText(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        SiteLine(DiveSite(id = site.id, name = site.name, type = site.type), MaterialTheme.typography.headlineSmall)
        Text(if (lat != null && lon != null) s.position(formatCoords(lat, lon)) else s.positionNotSet)
        Text(s.accessLine(site.access))
        site.maxDepthMeters?.let { Text(s.maxDepthLine(it)) }
        if (site.notes.isNotBlank()) Text(s.notesLine(site.notes))
        Button(onClick = onAddDive, modifier = Modifier.fillMaxWidth()) { Text(s.addDiveHere) }
        if (lat != null && lon != null) {
            OutlinedButton(
                onClick = { actions.openMap(lat, lon, site.name) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(s.openInMaps) }
        }
        SiteDivesSection(dives, onOpenDive)
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(s.edit) }
        OutlinedButton(onClick = onMerge, modifier = Modifier.fillMaxWidth()) { Text(s.mergeWithOther) }
        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(s.delete) }
    }
    if (confirm) {
        ConfirmDialog(
            if (dives.isEmpty()) s.confirmDelete(site.name) else s.confirmDeleteSiteWithDives(site.name, dives.size),
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun SiteForm(
    state: AppState,
    existing: DiveSite?,
    location: LocationProvider,
    actions: PlatformActions,
    pad: PaddingValues,
    onDone: () -> Unit,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    // Recherche des coordonnées par nom : null = pas de recherche ; sinon l'état de la fenêtre de résultats.
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var searchFailed by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var lat by remember { mutableStateOf(existing?.latitude?.toString() ?: "") }
    var lon by remember { mutableStateOf(existing?.longitude?.toString() ?: "") }
    var depth by remember { mutableStateOf(existing?.maxDepthMeters?.toString() ?: "") }
    var access by remember { mutableStateOf(existing?.access ?: SiteAccess.SHORE) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var country by remember { mutableStateOf(existing?.country ?: "") }
    var siteType by remember { mutableStateOf(existing?.type) }

    val latVal = lat.replace(',', '.').toDoubleOrNull()
    val lonVal = lon.replace(',', '.').toDoubleOrNull()
    // Les coordonnées sont facultatives (à compléter plus tard) mais, si on en donne, elles vont par paire et doivent être valides.
    val noCoords = lat.isBlank() && lon.isBlank()
    val coordsOk = latVal != null && latVal in -90.0..90.0 && lonVal != null && lonVal in -180.0..180.0
    val valid = name.isNotBlank() && (noCoords || coordsOk)

    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            name, { name = it }, label = { Text(s.siteName) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CountryField(country, { country = it }, remember(state.sites.size) { state.sites.map { it.country }.filter { it.isNotBlank() }.distinct() })
        Text(s.siteTypeLabel, style = MaterialTheme.typography.labelLarge)
        SiteTypeChips(siteType) { siteType = it }
        GpsFields(lat, lon, { lat = it }, { lon = it }, location)
        OutlinedButton(
            enabled = name.isNotBlank() && !searching,
            onClick = {
                searching = true
                searchFailed = false
                found = null
                showResults = true
                scope.launch {
                    // Le nom, avec le pays s'il est renseigné, pour départager les homonymes.
                    val query = listOf(name.trim(), country.trim()).filter { it.isNotEmpty() }.joinToString(", ")
                    val result = searchPlaces(actions, query, actions.systemLanguage())
                    searchFailed = result == null
                    found = result
                    searching = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("\uD83D\uDD0E  " + s.findCoords) }
        Text(s.positionOptionalHint, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            depth, { depth = it.filter(Char::isDigit) }, label = { Text(s.maxDepthField) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteAccess.entries.forEach { a ->
                FilterChip(selected = access == a, onClick = { access = a }, label = { Text(s.access(a)) })
            }
        }
        OutlinedTextField(
            notes, { notes = it }, label = { Text(s.notesField) }, minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = valid,
            onClick = {
                state.saveSite(
                    DiveSite(
                        id = existing?.id ?: state.newId(),
                        name = name.trim(),
                        latitude = if (noCoords) null else latVal,
                        longitude = if (noCoords) null else lonVal,
                        maxDepthMeters = depth.toIntOrNull(),
                        access = access,
                        notes = notes.trim(),
                        country = country.trim(),
                        type = siteType,
                    ),
                )
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.save) }
    }
    if (showResults) {
        AlertDialog(
            onDismissRequest = { showResults = false },
            title = { Text(s.findCoordsTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val list = found
                    when {
                        searching -> Text(s.findCoordsSearching)
                        searchFailed -> Text(s.findCoordsFailed, color = StatusKo)
                        list.isNullOrEmpty() -> Text(s.findCoordsNone)
                        else -> list.forEach { place ->
                            Card(
                                onClick = {
                                    lat = round5(place.latitude)
                                    lon = round5(place.longitude)
                                    showResults = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(place.title, style = MaterialTheme.typography.titleSmall)
                                    if (place.detail.isNotEmpty()) Text(place.detail, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        formatCoords(place.latitude, place.longitude),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    Text(s.findCoordsCredit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showResults = false }) { Text(s.cancel) } },
        )
    }
}

internal fun formatCoords(lat: Double, lon: Double) = "${round5(lat)}, ${round5(lon)}"

private fun round5(v: Double): String {
    val scaled = kotlin.math.round(v * 100000).toLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = kotlin.math.abs(scaled)
    return "$sign${abs / 100000}.${(abs % 100000).toString().padStart(5, '0')}"
}

// ----------------------------------------------------------- Check-lists

private fun ListStatus.color() = when (this) {
    ListStatus.OK -> StatusOk
    ListStatus.KO -> StatusKo
    ListStatus.IN_PROGRESS -> StatusNeutral
}

@Composable
private fun ListStatus.tint() = color().copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)

private fun Checklist.statusText(s: Strings): String = when (status) {
    ListStatus.OK -> s.statusOk
    ListStatus.KO -> s.statusKo(items.count { it.state == ItemState.NO })
    ListStatus.IN_PROGRESS -> s.statusInProgress(items.count { it.state == ItemState.TODO })
}

@Composable
private fun ChecklistList(state: AppState, pad: PaddingValues, onOpen: (Long) -> Unit, listState: LazyListState) {
    val s = LocalStrings.current
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(pad)) {
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.checklists, key = { it.id }) { c ->
                Card(
                    onClick = { onOpen(c.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = c.status.tint()),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            c.statusText(s),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = c.status.color(),
                        )
                    }
                }
            }
        }
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(s.newChecklist)
        }
    }
    if (adding) {
        TextPromptDialog(s.newChecklist, s.titleField, onDismiss = { adding = false }) {
            adding = false
            onOpen(state.addChecklist(it))
        }
    }
}

@Composable
private fun StatusBanner(list: Checklist) {
    val s = LocalStrings.current
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(list.status.color(), lerp(list.status.color(), Color(0xFF0B4F6C), 0.45f))))
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            list.statusText(s),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChecklistView(state: AppState, list: Checklist, pad: PaddingValues, onDeleted: () -> Unit) {
    val s = LocalStrings.current
    var addingItem by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<ChecklistItem?>(null) }
    var itemToEdit by remember { mutableStateOf<ChecklistItem?>(null) }

    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val currentList by rememberUpdatedState(list)

    fun onDrag(deltaY: Float) {
        val id = draggingId ?: return
        dragOffset += deltaY
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == id } ?: return
        val center = dragged.offset + dragOffset + dragged.size / 2f
        val target = visible.firstOrNull {
            it.key != id && center >= it.offset && center < it.offset + it.size
        } ?: return
        currentList.id.let { listId ->
            state.moveItem(listId, dragged.index, target.index)
        }
        // L'item déplacé change de position dans la liste : on compense pour qu'il reste sous le doigt.
        dragOffset += dragged.offset - target.offset
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        StatusBanner(list)
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            items(list.items, key = { it.id }) { item ->
                val isDragging = draggingId == item.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (isDragging) Modifier else Modifier.animateItem())
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.background,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "≡",
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .pointerInput(item.id) {
                                detectDragGestures(
                                    onDragStart = { draggingId = item.id; dragOffset = 0f },
                                    onDragEnd = { draggingId = null; dragOffset = 0f },
                                    onDragCancel = { draggingId = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                }
                            },
                    )
                    Column(Modifier.weight(1f).clickable { itemToEdit = item }) {
                        Text(item.text, style = MaterialTheme.typography.titleMedium)
                        item.state.caption(s)?.let { (label, color) ->
                            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                        }
                    }
                    TriStateBox(item.state) { state.cycleItem(list.id, item.id) }
                    TextButton(onClick = { itemToDelete = item }) { Text("✕") }
                }
                HorizontalDivider()
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { addingItem = true }, modifier = Modifier.fillMaxWidth()) { Text(s.addItem) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { state.resetChecklist(list.id) }, modifier = Modifier.weight(1f)) {
                    Text(s.resetAll, maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { renaming = true }, modifier = Modifier.weight(1f)) { Text(s.rename) }
            }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(s.deleteChecklist)
            }
        }
    }
    if (addingItem) {
        TextPromptDialog(s.newItem, s.textField, onDismiss = { addingItem = false }) {
            addingItem = false
            state.addItem(list.id, it)
        }
    }
    if (renaming) {
        TextPromptDialog(s.rename, s.titleField, initial = list.title, onDismiss = { renaming = false }) {
            renaming = false
            state.renameChecklist(list.id, it)
        }
    }
    itemToEdit?.let { item ->
        TextPromptDialog(s.editItem, s.textField, initial = item.text, onDismiss = { itemToEdit = null }) {
            itemToEdit = null
            state.editItem(list.id, item.id, it)
        }
    }
    itemToDelete?.let { item ->
        ConfirmDialog(
            s.confirmDelete(item.text),
            onConfirm = { state.deleteItem(list.id, item.id); itemToDelete = null },
            onDismiss = { itemToDelete = null },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            s.confirmDelete(list.title),
            onConfirm = { state.deleteChecklist(list.id); onDeleted() },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun ItemState.caption(s: Strings): Pair<String, Color>? = when (this) {
    ItemState.TODO -> null
    ItemState.YES -> s.stateYes to StatusOk
    ItemState.NO -> s.stateNo to StatusKo
    ItemState.NO_BUT_OK -> s.stateNoButOk to StatusWarn
}

/** Une seule case à 4 apparences : vide, coche verte, croix rouge, tiret ambre. Un appui passe à l'état suivant. */
@Composable
private fun TriStateBox(state: ItemState, onClick: () -> Unit) {
    val fill = when (state) {
        ItemState.TODO -> Color.Transparent
        ItemState.YES -> StatusOk
        ItemState.NO -> StatusKo
        ItemState.NO_BUT_OK -> StatusWarn
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(fill)
            .border(2.dp, if (state == ItemState.TODO) MaterialTheme.colorScheme.outline else fill, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 3.dp.toPx()
            val c = Color.White
            when (state) {
                ItemState.TODO -> Unit
                ItemState.YES -> {
                    drawLine(c, Offset(w * 0.1f, h * 0.55f), Offset(w * 0.4f, h * 0.85f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.4f, h * 0.85f), Offset(w * 0.92f, h * 0.15f), stroke, StrokeCap.Round)
                }
                ItemState.NO -> {
                    drawLine(c, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.85f, h * 0.85f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.15f, h * 0.85f), stroke, StrokeCap.Round)
                }
                ItemState.NO_BUT_OK ->
                    drawLine(c, Offset(w * 0.1f, h / 2), Offset(w * 0.9f, h / 2), stroke, StrokeCap.Round)
            }
        }
    }
}

// --------------------------------------------------------------- Dialogs

@Composable
internal fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(s.delete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        // Première lettre en majuscule, quelle que soit la façon dont le texte a été saisi.
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim().replaceFirstChar { it.uppercase() }) }) { Text(s.ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/**
 * Glissé horizontal d'une fiche vers la fiche voisine : la fiche suit le doigt, puis sort de l'écran pendant que la
 * voisine entre de l'autre côté. Les éléments qui gèrent eux-mêmes le glissé (courbe de plongée, cartes défilantes) le
 * consomment avant : seul un glissé ailleurs change de fiche. Sans voisine d'un côté, la fiche résiste puis revient.
 */
@Composable
private fun Modifier.swipeNeighbours(onRight: (() -> Unit)?, onLeft: (() -> Unit)?): Modifier {
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1f) }
    return this
        .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .graphicsLayer { translationX = offset.value }
        .pointerInput(onRight, onLeft) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val o = offset.value
                    val go = if (o > threshold) onRight else if (o < -threshold) onLeft else null
                    scope.launch {
                        if (go != null) {
                            val dir = if (o > 0) 1f else -1f
                            offset.animateTo(dir * width, tween(140))
                            go()
                            offset.snapTo(-dir * width)
                            offset.animateTo(0f, tween(200))
                        } else offset.animateTo(0f, tween(180))
                    }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
                onHorizontalDrag = { _, delta ->
                    val next = offset.value + delta
                    val blocked = (next > 0 && onRight == null) || (next < 0 && onLeft == null)
                    scope.launch { offset.snapTo(offset.value + if (blocked) delta / 4 else delta) }
                },
            )
        }
}
