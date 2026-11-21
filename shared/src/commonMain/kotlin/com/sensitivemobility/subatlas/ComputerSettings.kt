package com.sensitivemobility.subatlas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Réglages > « Importer depuis un ordinateur de plongée » : l'association d'un ordinateur (Peregrine) à l'appli. Une fois associé, l'appli
 * connaît son adresse Bluetooth et n'a plus à le chercher parmi tous les appareils. « Dissocier » efface l'association.
 * Parcours voulu : docs/import-ordinateur-plongee.md.
 */
@Composable
internal fun ComputersSection(state: AppState, actions: PlatformActions, importer: ComputerImport) {
    val ble = actions.ble ?: return
    val s = LocalStrings.current
    var associating by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    val peregrine = state.settings.computers.firstOrNull { it.family == DiveComputer.SHEARWATER }

    Text(
        s.computersSection,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(s.computersHint, style = MaterialTheme.typography.bodyMedium)
    Text(s.peregrineTitle, style = MaterialTheme.typography.titleMedium)
    if (peregrine == null) {
        Text(s.computerNone, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { associating = true }, modifier = Modifier.fillMaxWidth()) { Text(s.computerAssociate) }
    } else {
        Text(peregrine.label(), style = MaterialTheme.typography.bodyLarge)
        Text(s.computerAssociated, style = MaterialTheme.typography.bodySmall)
        // Import automatique : sans demander de confirmation, avec une jauge et des messages.
        Row(
            Modifier.fillMaxWidth().clickable {
                state.updateSettings { it.copy(computers = it.computers.map { c -> if (c.address == peregrine.address) c.copy(autoImport = !c.autoImport) else c }) }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = peregrine.autoImport, onCheckedChange = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(s.computerAuto, style = MaterialTheme.typography.bodyLarge)
                Text(s.computerAutoHint, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(s.computerDetectHint, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { importer.start(peregrine) }, enabled = importer.idle, modifier = Modifier.fillMaxWidth()) { Text(s.computerImportNow) }
        OutlinedButton(onClick = { forgetting = true }, modifier = Modifier.fillMaxWidth()) { Text(s.computerForget) }
    }

    if (associating) {
        AssociateDialog(
            ble,
            onAssociated = { computer ->
                // Un seul Peregrine associé : une nouvelle association remplace l'ancienne.
                state.updateSettings { it.copy(computers = it.computers.filter { c -> c.family != computer.family } + computer) }
                associating = false
            },
            onDismiss = { associating = false },
        )
    }
    if (forgetting && peregrine != null) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(s.computerForgetTitle) },
            text = { Text(s.computerForgetMessage(peregrine.label())) },
            confirmButton = {
                TextButton(onClick = {
                    state.updateSettings { it.copy(computers = it.computers.filter { c -> c.address != peregrine.address }) }
                    forgetting = false
                }) { Text(s.computerForgetConfirm, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(s.cancel) } },
        )
    }
}

/**
 * Cherche les Peregrine à portée, puis, quand on en touche un, s'y connecte pour lire son numéro de série, son modèle et son firmware
 * (preuve que c'est bien un ordinateur Shearwater) avant d'enregistrer l'association.
 */
@Composable
private fun AssociateDialog(ble: BleTransport, onAssociated: (DiveComputer) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    HoldComputerLock()
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<BleFound>() }
    var scanning by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf<BleConnection?>(null) }

    DisposableEffect(Unit) { onDispose { connection?.close() } }

    fun scan() {
        scope.launch {
            devices.clear()
            error = null
            try {
                if (!ble.ensurePermissions()) {
                    error = s.blePermissionDenied
                    return@launch
                }
                scanning = true
                ble.scan(10_000) { found -> if (devices.none { it.address == found.address }) devices += found }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                scanning = false
                searched = true
            }
        }
    }

    fun associate(device: BleFound) {
        scope.launch {
            checking = true
            error = null
            try {
                val c = ble.connect(device.address) { println("SubAtlas BLE : $it") }
                connection = c
                val link = ShearwaterBle(c) { println("SubAtlas BLE : $it") }
                val serial = link.read(0x8010).decodeToString().trim()
                val firmware = runCatching { link.read(0x8011).decodeToString().trim() }.getOrDefault("")
                val modelId = runCatching { link.read(0x8060).firstOrNull()?.toInt()?.and(0xFF) }.getOrNull()
                c.close()
                connection = null
                onAssociated(
                    DiveComputer(
                        address = device.address,
                        name = device.name,
                        model = ShearwaterBleImport.modelName(modelId),
                        serial = serial,
                        firmware = firmware,
                        family = DiveComputer.SHEARWATER,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connection?.close()
                connection = null
                error = s.computerAssociateFailed(e.message.orEmpty())
            } finally {
                checking = false
            }
        }
    }

    // La recherche démarre à l'ouverture : l'utilisateur a déjà activé le Bluetooth de l'ordinateur.
    LaunchedEffect(Unit) { scan() }

    val shown = devices.filter { showAll || it.isShearwater }.sortedByDescending { it.rssi }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(s.associateTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scanning || checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    when {
                        checking -> s.associateChecking
                        scanning -> s.bleScanning
                        else -> s.associateIntro
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                for (d in shown) {
                    Text(
                        "${d.name.ifBlank { d.address }}  (${d.rssi} dBm)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !checking) { associate(d) }.padding(vertical = 10.dp),
                    )
                }
                if (searched && !scanning && shown.isEmpty() && error == null) {
                    Text(s.associateNone, style = MaterialTheme.typography.bodyMedium)
                    Text(s.associateSeen(devices.size), style = MaterialTheme.typography.bodySmall)
                }
                if (searched && !scanning && !showAll && devices.any { !it.isShearwater }) {
                    TextButton(onClick = { showAll = true }) { Text(s.associateShowAll) }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = { scan() }, enabled = !scanning && !checking) { Text(s.bleScan) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text(s.cancel) } },
    )
}
