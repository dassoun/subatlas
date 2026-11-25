package com.sensitivemobility.subatlas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Téléchargement des plongées d'un ordinateur Shearwater en Bluetooth LE : recherche, connexion, sommaire des plongées (celles qui
 * ne sont pas encore dans le carnet sont cochées), puis téléchargement de celles qu'on a cochées. Elles passent ensuite par l'aperçu
 * d'import habituel ([onDives]). Un journal visible (et partageable) décrit chaque échange ; les relevés bruts sont enregistrés dans
 * le stockage de l'appli (`files/ble/`).
 */
@Composable
internal fun ShearwaterBleDialog(
    ble: BleTransport,
    actions: PlatformActions,
    state: AppState,
    onDives: (List<ShearwaterDive>) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    HoldComputerLock()
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<BleFound>() }
    val entries = remember { mutableStateListOf<ShearwaterManifestEntry>() }
    val imported = remember { mutableStateListOf<Int>() } // index des plongées déjà dans le carnet
    val selected = remember { mutableStateListOf<Int>() }
    val logLines = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    // Avancement du téléchargement (0 à 1) ; null : durée inconnue (connexion, lecture du sommaire), barre indéterminée.
    var fraction by remember { mutableStateOf<Float?>(null) }
    var summary by remember { mutableStateOf("") }
    var connection by remember { mutableStateOf<BleConnection?>(null) }
    var link by remember { mutableStateOf<ShearwaterBle?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var base by remember { mutableStateOf(0L) }
    var serial by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Plongées déjà lues (et leur index dans le sommaire) : une coupure en cours de route ne les fait pas relire.
    val downloaded = remember { mutableStateListOf<ShearwaterDive>() }
    val downloadedIndexes = remember { mutableStateListOf<Int>() }

    fun log(line: String) {
        println("SubAtlas BLE : $line")
        logLines += line
    }

    /** Une cause d'échec lisible : l'utilisateur ne doit pas lire un code Bluetooth. */
    fun reason(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "déconnecté" in m -> "appareil hors de portée ou éteint"
            "pas de réponse" in m -> "l'ordinateur ne répond plus"
            "délai dépassé" in m -> "ordinateur introuvable"
            m.isNotBlank() -> m
            else -> e::class.simpleName.orEmpty()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            connection?.close()
            actions.setKeepScreenOn(false)
        }
    }

    fun scan() {
        scope.launch {
            devices.clear()
            try {
                if (!ble.ensurePermissions()) {
                    log(s.blePermissionDenied)
                    return@launch
                }
                scanning = true
                log(s.bleScanning)
                ble.scan(8_000) { found -> if (devices.none { it.address == found.address }) devices += found }
                log("${devices.size} appareil(s) trouvé(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("recherche : ${e.message}")
            } finally {
                scanning = false
            }
        }
    }

    /**
     * Revient à l'écran de recherche : après une coupure, l'ordinateur a arrêté de transmettre, il faut repartir d'une connexion neuve
     * (pas de reprise automatique). Les plongées déjà lues, si on ne les a pas importées, sont perdues.
     */
    fun restart() {
        job?.cancel()
        connection?.close()
        connection = null
        link = null
        entries.clear()
        imported.clear()
        selected.clear()
        downloaded.clear()
        downloadedIndexes.clear()
        devices.clear()
        error = null
        progress = ""
        fraction = null
        busy = false
    }

    fun connect(device: BleFound) {
        job = scope.launch {
            busy = true
            error = null
            entries.clear()
            imported.clear()
            selected.clear()
            downloaded.clear()
            downloadedIndexes.clear()
            connection?.close()
            try {
                progress = s.bleConnecting
                fraction = null
                val c = ble.connect(device.address) { log(it) }
                connection = c
                val l = ShearwaterBle(c) { log(it) }
                link = l
                suspend fun identity(label: String, id: Int, text: Boolean): ByteArray? = try {
                    l.read(id).also { log("$label : ${if (text) it.decodeToString().trim() else ShearwaterBle.hex(it)}") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("$label : ${e.message}")
                    null
                }
                serial = identity("numéro de série", 0x8010, true)?.decodeToString()?.trim().orEmpty()
                val firmware = identity("firmware", 0x8011, true)?.decodeToString()?.trim().orEmpty()
                modelId = identity("modèle", 0x8060, false)?.firstOrNull()?.toInt()?.and(0xFF)
                val format = identity("format du carnet", 0x8021, false)
                val formatId = if (format != null && format.size >= 5) {
                    ((format[1].toLong() and 0xFF) shl 24) or ((format[2].toLong() and 0xFF) shl 16) or ((format[3].toLong() and 0xFF) shl 8) or (format[4].toLong() and 0xFF)
                } else {
                    0L
                }
                // Les anciens formats (0xDD000000, 0xC0000000, 0x90000000) comptent depuis 0xC0000000 ; le format natif (0x80000000) depuis sa propre valeur.
                base = when (formatId) {
                    0xDD000000L, 0xC0000000L, 0x90000000L -> 0xC0000000L
                    else -> formatId
                }
                summary = s.bleSummary(ShearwaterBleImport.computerName(modelId, serial), firmware)

                progress = s.bleReadingList
                val manifest = l.manifest { n -> progress = "${s.bleReadingList} ($n)" }
                ble.saveDump("manifest.bin", manifest)
                val all = ShearwaterManifestEntry.parse(manifest).filter { it.valid }
                entries.addAll(all)
                val dives = state.dives.toList()
                for (e in all) {
                    if (ShearwaterBleImport.alreadyImported(e, serial, dives)) imported += e.index else selected += e.index
                }
                log("sommaire : ${all.size} plongée(s), ${imported.size} déjà dans le carnet")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("erreur : ${e.message ?: e::class.simpleName}")
                error = s.bleLost(reason(e))
            } finally {
                busy = false
                progress = ""
                fraction = null
            }
        }
    }

    fun download() {
        job = scope.launch {
            busy = true
            error = null
            actions.setKeepScreenOn(true)
            try {
                val l = link ?: throw IllegalStateException("pas de connexion")
                val picks = entries.filter { it.index in selected && it.index !in downloadedIndexes }
                // La taille attendue d'une plongée est celle de sa zone de mémoire (adresses de début et de fin du sommaire).
                fun expected(e: ShearwaterManifestEntry) = maxOf(1L, e.endAddress - e.address)
                val total = picks.sumOf { expected(it) }
                var done = 0L
                picks.forEachIndexed { i, e ->
                    fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                    progress = s.bleProgress(i + 1, picks.size, (fraction!! * 100).toInt())
                    val raw = l.download(base + e.address, ShearwaterBle.DIVE_MAX_SIZE, compression = true) { n ->
                        val f = ((done + minOf(n.toLong(), expected(e))).toFloat() / total).coerceIn(0f, 1f)
                        fraction = f
                        progress = s.bleProgress(i + 1, picks.size, (f * 100).toInt())
                    }
                    done += expected(e)
                    ble.saveDump("dive-${e.fingerprint.toString(16)}.bin", raw)
                    val dive = ShearwaterBleImport.toDive(e, raw, serial, modelId)
                    if (dive != null) downloaded += dive else log("plongée ${e.logNumber} : données illisibles (${raw.size} octets)")
                    downloadedIndexes += e.index
                    log("plongée ${e.logNumber} : ${raw.size} octets")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Coupure (appareil éloigné, éteint, Bluetooth coupé) ou silence de l'appareil : rien n'est perdu de ce qui est déjà lu.
                log("erreur : ${e.message ?: e::class.simpleName}")
                error = s.bleLost(reason(e))
            } finally {
                busy = false
                progress = ""
                fraction = null
                actions.setKeepScreenOn(false)
            }
            // Tout est lu : l'aperçu d'import s'ouvre.
            if (error == null && downloaded.isNotEmpty()) {
                connection?.close()
                onDives(downloaded.toList())
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(s.bleTitle) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // L'état de la connexion et du téléchargement, toujours visible au-dessus de la liste.
            if (busy) {
                fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (progress.isNotEmpty()) Text(progress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) {
                    Text(s.bleIntro, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { scan() }, enabled = !scanning && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (scanning) s.bleScanning else s.bleScan)
                    }
                    for (d in devices.sortedByDescending { it.rssi }) {
                        Text(
                            "${d.name}  (${d.rssi} dBm)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { connect(d) }.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    Text(summary, style = MaterialTheme.typography.titleSmall)
                    Text(s.bleKeepScreen, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selected.clear(); selected.addAll(entries.map { it.index }.filter { it !in imported }) }, enabled = !busy) { Text(s.bleSelectNew) }
                        TextButton(onClick = { selected.clear() }, enabled = !busy) { Text(s.bleSelectNone) }
                    }
                    for (e in entries) {
                        val (date, time) = ShearwaterBleImport.dateTime(e.fingerprint)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { if (e.index in selected) selected.remove(e.index) else selected.add(e.index) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = e.index in selected, onCheckedChange = null, enabled = !busy)
                            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                                Text("${s.dateText(date)} $time · ${e.durationSeconds / 60} min · max ${LocalUnits.current.depth.text(e.maxDepthDm / 10.0)}", style = MaterialTheme.typography.bodyMedium)
                                if (e.index in imported) Text(s.bleAlready, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Text(s.bleLog, style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                    for (line in logLines) Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
          }
        },
        confirmButton = {
            if (busy && entries.isNotEmpty()) {
                TextButton(onClick = { job?.cancel(); connection?.close(); busy = false; progress = ""; fraction = null; actions.setKeepScreenOn(false) }) { Text(s.bleStop) }
            } else if (entries.isNotEmpty() && error != null) {
                // Après une coupure : on repart d'une connexion neuve.
                Button(onClick = { restart() }) { Text(s.bleRestart) }
            } else if (entries.isNotEmpty()) {
                val remaining = selected.count { it !in downloadedIndexes }
                Button(onClick = { download() }, enabled = remaining > 0) { Text(s.bleDownload(remaining)) }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(s.photoClose) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { actions.shareText(logLines.joinToString("\n")) }, enabled = logLines.isNotEmpty()) { Text(s.bleCopyLog) }
                if (downloaded.isNotEmpty() && !busy) TextButton(onClick = { connection?.close(); onDives(downloaded.toList()) }) { Text(s.bleImportRead(downloaded.size)) }
                if (entries.isNotEmpty() && !busy) TextButton(onClick = onDismiss) { Text(s.photoClose) }
            }
        },
    )
}
