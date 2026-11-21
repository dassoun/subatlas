package com.sensitivemobility.subatlas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Import des nouvelles plongées d'un ordinateur de plongée associé (voir docs/import-ordinateur-plongee.md) : connexion, lecture du
 * sommaire, puis, selon le réglage de l'ordinateur, une confirmation de l'utilisateur ou un import automatique, avec jauge et messages.
 * Les plongées lues sont ajoutées au carnet avec l'import habituel (`AppState.planShearwaterImport` / `applyShearwaterImport`).
 */
internal class ComputerImport(
    private val state: AppState,
    private val actions: PlatformActions,
    private val scope: CoroutineScope,
    private val strings: () -> Strings,
) {
    enum class Stage { IDLE, CONNECTING, READING, CONFIRM, DOWNLOADING, APPLYING, NONE, DONE, FAILED }

    var stage by mutableStateOf(Stage.IDLE)
        private set

    /** Le titre de la fenêtre : le modèle de l'ordinateur. */
    var title by mutableStateOf("")
        private set

    /** Le message du traitement en cours (étape, plongée en cours, pourcentage). */
    var progress by mutableStateOf("")
        private set

    /** Avancement du téléchargement (0 à 1) ; `null` : durée inconnue (connexion, lecture), barre indéterminée. */
    var fraction by mutableStateOf<Float?>(null)
        private set

    /** Le résultat (« 3 plongées ajoutées… ») ou la cause d'un échec. */
    var message by mutableStateOf("")
        private set

    /** Les plongées nouvelles proposées à l'utilisateur, et celles qu'il a cochées (index du sommaire). */
    val fresh = mutableStateListOf<ShearwaterManifestEntry>()
    val selected = mutableStateListOf<Int>()

    /** Instant (ms) de la fin du dernier import : la détection ne se redéclenche pas aussitôt. */
    var lastEnd = 0L
        private set

    private var computer: DiveComputer? = null
    private var session: ShearwaterSession? = null
    private var job: Job? = null

    /** Le journal des échanges avec l'ordinateur : partageable depuis la fenêtre en cas d'échec. */
    private val journal = ArrayList<String>()

    private fun note(line: String) {
        println("SubAtlas BLE : $line")
        journal += line
    }

    /** Envoie le journal des échanges (feuille de partage du système). */
    fun shareLog() = actions.shareText(journal.joinToString("\n"))

    val idle: Boolean get() = stage == Stage.IDLE

    /** Lance la recherche des nouvelles plongées de [c] (détection de l'ordinateur ou bouton). Sans effet si un import est en cours. */
    fun start(c: DiveComputer) {
        val ble = actions.ble ?: return
        if (stage != Stage.IDLE) return
        computer = c
        title = c.model.ifBlank { c.name }
        stage = Stage.CONNECTING
        progress = strings().computerImportConnecting
        fraction = null
        message = ""
        fresh.clear()
        selected.clear()
        journal.clear()
        job = scope.launch {
            val s = ShearwaterSession(ble, c.address) { note(it) }
            session = s
            actions.setKeepScreenOn(true)
            try {
                s.open()
                stage = Stage.READING
                progress = strings().computerImportReading
                val entries = s.manifest()
                val news = s.fresh(entries, state.dives.toList())
                when {
                    news.isEmpty() -> {
                        s.close()
                        finish(Stage.NONE, strings().computerImportNone)
                    }
                    c.autoImport -> downloadAndApply(s, news)
                    else -> {
                        // La connexion reste ouverte en attendant la réponse : après l'envoi de la liste, l'ordinateur (« Liste envoyée »)
                        // attend les demandes de téléchargement sur cette même connexion et n'accepte pas de recommencer à zéro.
                        fresh.addAll(news)
                        selected.addAll(news.map { it.index })
                        stage = Stage.CONFIRM
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.close()
                finish(Stage.FAILED, strings().computerImportFailed(describeBleFailure(e)), failed = true)
            }
        }
    }

    /** L'utilisateur a confirmé l'import des plongées cochées. */
    fun confirm() {
        if (stage != Stage.CONFIRM) return
        val s = session ?: return
        val picks = fresh.filter { it.index in selected }
        if (picks.isEmpty()) return
        stage = Stage.DOWNLOADING
        progress = strings().bleProgress(1, picks.size, 0)
        fraction = 0f
        job = scope.launch {
            try {
                downloadAndApply(s, picks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.close()
                finish(Stage.FAILED, strings().computerImportFailed(describeBleFailure(e)), failed = true)
            }
        }
    }

    private suspend fun downloadAndApply(s: ShearwaterSession, picks: List<ShearwaterManifestEntry>) {
        stage = Stage.DOWNLOADING
        val total = picks.sumOf { ShearwaterSession.expectedSize(it) }
        var done = 0L
        val dives = ArrayList<ShearwaterDive>()
        var failure: String? = null
        try {
            picks.forEachIndexed { i, e ->
                fun report(f: Float) {
                    fraction = f.coerceIn(0f, 1f)
                    progress = strings().bleProgress(i + 1, picks.size, (f.coerceIn(0f, 1f) * 100).toInt())
                }
                report(done.toFloat() / total)
                val raw = s.download(e) { n -> report((done + minOf(n.toLong(), ShearwaterSession.expectedSize(e))).toFloat() / total) }
                done += ShearwaterSession.expectedSize(e)
                s.toDive(e, raw)?.let { dives += it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Une coupure en cours de route : les plongées déjà lues sont tout de même ajoutées ; les autres seront proposées la prochaine fois.
            failure = describeBleFailure(e)
        } finally {
            s.close()
        }
        var text = ""
        if (dives.isNotEmpty()) {
            stage = Stage.APPLYING
            fraction = null
            progress = strings().computerImportApplying
            val plan = state.planShearwaterImport(dives)
            val (created, merged) = state.applyShearwaterImport(plan)
            text = strings().shearwaterDone(created, merged)
        }
        if (failure != null) {
            finish(Stage.FAILED, strings().computerImportFailed(failure) + if (text.isNotEmpty()) " $text" else "", failed = true)
        } else {
            finish(Stage.DONE, text.ifEmpty { strings().computerImportNone })
        }
    }

    private fun finish(next: Stage, text: String, failed: Boolean = false) {
        message = text
        progress = ""
        fraction = null
        stage = next
        lastEnd = Clock.System.now().toEpochMilliseconds()
        actions.setKeepScreenOn(false)
    }

    /** Ferme la fenêtre : arrête l'import en cours, s'il y en a un. */
    fun dismiss() {
        job?.cancel()
        session?.close()
        session = null
        actions.setKeepScreenOn(false)
        lastEnd = Clock.System.now().toEpochMilliseconds()
        stage = Stage.IDLE
        progress = ""
        fraction = null
    }
}

/** Les fenêtres de Bluetooth tenues ouvertes par l'utilisateur (association, import manuel) : la détection se met en pause pendant ce temps. */
internal object ComputerLock {
    var holders by mutableStateOf(0)
}

@Composable
internal fun HoldComputerLock() {
    DisposableEffect(Unit) {
        ComputerLock.holders++
        onDispose { ComputerLock.holders-- }
    }
}

/**
 * La détection de l'ordinateur associé, **tant que l'appli est ouverte** : elle guette son adresse Bluetooth (recherche filtrée) et lance
 * l'import dès qu'il apparaît (Bluetooth activé sur l'ordinateur). Elle ne se redéclenche pas tant que l'ordinateur reste à portée :
 * il faut qu'il disparaisse un moment (Bluetooth coupé sur l'ordinateur) pour qu'une nouvelle apparition compte.
 */
@Composable
internal fun ComputerWatcher(state: AppState, actions: PlatformActions, importer: ComputerImport) {
    val ble = actions.ble ?: return
    val computer = state.settings.computers.firstOrNull { it.family == DiveComputer.SHEARWATER } ?: return
    val foreground by actions.appInForeground.collectAsState()
    val armed = remember(computer.address) { booleanArrayOf(true) }
    val locked = ComputerLock.holders > 0
    LaunchedEffect(computer.address, foreground, locked) {
        if (!foreground || locked) return@LaunchedEffect
        // De retour dans l'appli longtemps après le dernier import : une nouvelle apparition compte de nouveau.
        if (Clock.System.now().toEpochMilliseconds() - importer.lastEnd > REARM_AFTER_MS) armed[0] = true
        while (isActive) {
            if (!importer.idle) {
                delay(500)
                continue
            }
            val seen = try {
                ble.waitForDevice(computer.address, if (armed[0]) 60_000 else 15_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5_000)
                false
            }
            if (armed[0] && seen && importer.idle && ComputerLock.holders == 0) {
                armed[0] = false
                importer.start(computer)
            } else if (!armed[0] && !seen) {
                armed[0] = true
            }
        }
    }
}

private const val REARM_AFTER_MS = 5 * 60_000L

@Composable
internal fun ComputerImportDialog(importer: ComputerImport) {
    if (importer.idle) return
    val s = LocalStrings.current
    val units = LocalUnits.current
    val stage = importer.stage
    // « Aucune nouvelle plongée » se referme seul : ce n'est qu'une information.
    if (stage == ComputerImport.Stage.NONE) LaunchedEffect(Unit) {
        delay(3_000)
        if (importer.stage == ComputerImport.Stage.NONE) importer.dismiss()
    }
    val working = stage in listOf(ComputerImport.Stage.CONNECTING, ComputerImport.Stage.READING, ComputerImport.Stage.DOWNLOADING, ComputerImport.Stage.APPLYING)
    AlertDialog(
        onDismissRequest = { if (!working) importer.dismiss() },
        title = { Text(importer.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    working -> {
                        importer.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                            ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(importer.progress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        if (stage == ComputerImport.Stage.DOWNLOADING) Text(s.bleKeepScreen, style = MaterialTheme.typography.bodySmall)
                    }
                    stage == ComputerImport.Stage.CONFIRM -> {
                        Text(s.computerImportFound(importer.fresh.size), style = MaterialTheme.typography.bodyMedium)
                        for (e in importer.fresh) {
                            val (date, time) = ShearwaterBleImport.dateTime(e.fingerprint)
                            Row(
                                Modifier.fillMaxWidth().clickable { if (e.index in importer.selected) importer.selected.remove(e.index) else importer.selected.add(e.index) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = e.index in importer.selected, onCheckedChange = null)
                                Text(
                                    "${s.dateText(date)} $time · ${e.durationSeconds / 60} min · max ${units.depth.text(e.maxDepthDm / 10.0)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                    stage == ComputerImport.Stage.FAILED -> Text(importer.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> Text(importer.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when {
                working && stage != ComputerImport.Stage.APPLYING -> TextButton(onClick = { importer.dismiss() }) { Text(s.bleStop) }
                stage == ComputerImport.Stage.CONFIRM -> Button(onClick = { importer.confirm() }, enabled = importer.selected.isNotEmpty()) {
                    Text(s.computerImportAction(importer.selected.size))
                }
                !working -> TextButton(onClick = { importer.dismiss() }) { Text(s.ok) }
            }
        },
        dismissButton = {
            when (stage) {
                ComputerImport.Stage.CONFIRM -> TextButton(onClick = { importer.dismiss() }) { Text(s.computerImportLater) }
                ComputerImport.Stage.FAILED -> TextButton(onClick = { importer.shareLog() }) { Text(s.bleCopyLog) }
                else -> {}
            }
        },
    )
}
