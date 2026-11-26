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
import androidx.compose.runtime.key
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
 * Réglages > « Importer depuis un ordinateur de plongée » : un bloc par ordinateur associé (toutes marques), puis le bouton qui en
 * associe un autre. Une fois associé, l'appli connaît son adresse Bluetooth et n'a plus à le chercher parmi tous les appareils.
 * Parcours voulu : docs/import-ordinateur-plongee.md.
 */
@Composable
internal fun ComputersSection(state: AppState, actions: PlatformActions, importer: ComputerImport) {
    val ble = actions.ble ?: return
    val s = LocalStrings.current
    var associating by remember { mutableStateOf(false) }

    Text(
        s.computersSection,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(s.computersHint, style = MaterialTheme.typography.bodyMedium)
    if (state.settings.computers.isEmpty()) Text(s.computerNone, style = MaterialTheme.typography.bodyMedium)
    for (computer in state.settings.computers) {
        key(computer.address) { PairedComputer(computer, state, importer) }
    }
    OutlinedButton(onClick = { associating = true }, modifier = Modifier.fillMaxWidth()) { Text(s.computerAssociate) }

    if (associating) {
        AssociateDialog(
            ble,
            onAssociated = { computer, session, log ->
                // Le même appareil associé à nouveau remplace son ancienne entrée.
                state.updateSettings { it.copy(computers = it.computers.filterNot { c -> c.sameDevice(computer) } + computer) }
                associating = false
                // L'import s'enchaîne sur la même connexion : un Shearwater, une fois coupé, ne répond plus avant réactivation.
                importer.start(computer, session, log)
            },
            onDismiss = { associating = false },
        )
    }
}

/** Un ordinateur associé : son identité, l'import automatique, l'import à la demande et la dissociation. */
@Composable
private fun PairedComputer(computer: DiveComputer, state: AppState, importer: ComputerImport) {
    val s = LocalStrings.current
    var forgetting by remember { mutableStateOf(false) }

    Text(computer.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(computer.label(), style = MaterialTheme.typography.bodyLarge)
    Text(s.computerAssociated, style = MaterialTheme.typography.bodySmall)
    // Import automatique : sans demander de confirmation, avec une jauge et des messages.
    Row(
        Modifier.fillMaxWidth().clickable {
            state.updateSettings { it.copy(computers = it.computers.map { c -> if (c.address == computer.address) c.copy(autoImport = !c.autoImport) else c }) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = computer.autoImport, onCheckedChange = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(s.computerAuto, style = MaterialTheme.typography.bodyLarge)
            Text(s.computerAutoHint, style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(s.computerDetectHint(computer.displayName), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { importer.start(computer) }, enabled = importer.idle, modifier = Modifier.fillMaxWidth()) { Text(s.computerImportNow) }
    OutlinedButton(onClick = { forgetting = true }, modifier = Modifier.fillMaxWidth()) { Text(s.computerForget(computer.displayName)) }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(s.computerForgetTitle(computer.displayName)) },
            text = { Text(s.computerForgetMessage(computer.label())) },
            confirmButton = {
                TextButton(onClick = {
                    state.updateSettings { it.copy(computers = it.computers.filter { c -> c.address != computer.address }) }
                    forgetting = false
                }) { Text(s.computerForgetConfirm, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(s.cancel) } },
        )
    }
}

/**
 * Cherche les ordinateurs de plongée à portée, puis, quand on en touche un, s'y connecte avec le pilote de sa marque pour lire son
 * identité (preuve que c'est bien un ordinateur pris en charge) avant d'enregistrer l'association. La session reste ouverte :
 * [onAssociated] la reçoit, avec son journal, et doit s'en servir ou la fermer.
 */
@Composable
private fun AssociateDialog(
    ble: BleTransport,
    onAssociated: (DiveComputer, ComputerSession, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    HoldComputerLock()
    val scope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<BleFound>() }
    var scanning by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // La session en cours de vérification : fermée avec la fenêtre, sauf si elle a été remise à l'import.
    var pending by remember { mutableStateOf<ComputerSession?>(null) }

    DisposableEffect(Unit) { onDispose { pending?.close() } }

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
                val log = ArrayList<String>()
                // Un appareil non reconnu (liste « tous les appareils ») est essayé comme un Shearwater.
                val driver = driverFor(device) ?: ShearwaterDriver
                val session = driver.session(ble, device.address)
                session.log = { println("SubAtlas BLE : $it"); log += it }
                pending = session
                session.open()
                pending = null
                onAssociated(
                    DiveComputer(
                        address = device.address,
                        name = device.name,
                        model = session.model,
                        serial = session.serial,
                        firmware = session.firmware,
                        family = driver.family,
                    ),
                    session,
                    log,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending?.close()
                pending = null
                error = s.computerAssociateFailed(e.message.orEmpty())
            } finally {
                checking = false
            }
        }
    }

    // La recherche démarre à l'ouverture : l'utilisateur a déjà activé le Bluetooth de l'ordinateur.
    LaunchedEffect(Unit) { scan() }

    val shown = devices.filter { showAll || driverFor(it) != null }.sortedByDescending { it.rssi }
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
                if (searched && !scanning && !showAll && devices.any { driverFor(it) == null }) {
                    TextButton(onClick = { showAll = true }) { Text(s.associateShowAll) }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = { scan() }, enabled = !scanning && !checking) { Text(s.bleScan) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text(s.cancel) } },
    )
}
