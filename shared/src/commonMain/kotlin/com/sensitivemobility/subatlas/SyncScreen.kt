package com.sensitivemobility.subatlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Section « Synchronisation » de Réglages > Données, avec ses boîtes de dialogue (aperçu, progression, rapport). */
@Composable
internal fun SyncSection(sync: SyncController) {
    if (!sync.available) return
    val t = LocalStrings.current.sync
    SectionTitle(t.title)
    Text(t.hint, style = MaterialTheme.typography.bodyMedium)

    if (sync.connected) {
        val account = sync.sync.account
        Text(t.connectedAs(account), style = MaterialTheme.typography.titleSmall)
        Text(
            sync.sync.lastSync.takeIf { it.isNotBlank() }?.let { t.lastSync(localDateTime(it)) } ?: t.neverSynced,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = sync::start, enabled = sync.ui !is SyncUi.Working, modifier = Modifier.fillMaxWidth()) { Text(t.syncNow) }
        var confirmDisconnect by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) { Text(t.disconnect) }
        if (confirmDisconnect) {
            AlertDialog(
                onDismissRequest = { confirmDisconnect = false },
                title = { Text(t.disconnectTitle) },
                text = { Text(t.disconnectMessage) },
                confirmButton = { TextButton(onClick = { confirmDisconnect = false; sync.disconnect() }) { Text(t.disconnect) } },
                dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(LocalStrings.current.cancel) } },
            )
        }
    } else {
        OutlinedTextField(
            sync.serverDraft, { sync.serverDraft = it }, label = { Text(t.serverLabel) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (sync.debugBuild) {
            TextButton(onClick = { sync.serverDraft = "http://10.0.2.2:8080" }) { Text(t.useEmulator) }
        }
        OutlinedTextField(
            sync.codeDraft, { sync.codeDraft = it }, label = { Text(t.codeLabel) }, singleLine = true,
            supportingText = { Text(t.codeHelp) }, modifier = Modifier.fillMaxWidth(),
        )
        if (sync.linkReceived) Text(t.linkReceived, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        sync.connectError?.let { e ->
            val text = when (e) {
                ConnectError.INVALID_SERVER -> t.errInvalidServer
                ConnectError.INSECURE -> t.errInsecure
                ConnectError.INVALID_CODE -> t.errInvalidCode
                ConnectError.NETWORK -> t.errNetwork
                ConnectError.RATE_LIMITED -> t.errRateLimited(60)
                ConnectError.OTHER -> t.errApi("", sync.connectMessage)
            }
            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = sync::connect, enabled = sync.codeDraft.isNotBlank() && !sync.connecting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (sync.connecting) t.connecting else t.connect) }
    }
    SyncDialogs(sync)
}

private fun localDateTime(iso: String): String {
    val instant = parseInstant(iso) ?: return iso
    val d = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${d.year}-${two((d.month.ordinal + 1))}-${two(d.day)} ${two(d.hour)}:${two(d.minute)}"
}

@Composable
private fun SyncDialogs(sync: SyncController) {
    val t = LocalStrings.current.sync
    when (val ui = sync.ui) {
        is SyncUi.Working -> {
            val p = ui.progress
            AlertDialog(
                onDismissRequest = {},
                title = { Text(t.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(phaseText(p.phase), style = MaterialTheme.typography.titleSmall)
                        if (p.total > 0) {
                            LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(t.progress(p.done, p.total), style = MaterialTheme.typography.bodySmall)
                        if (p.waitSeconds > 0) Text(t.waiting(p.waitSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = { TextButton(onClick = sync::cancel) { Text(t.cancelSync) } },
            )
        }
        is SyncUi.Preview -> PreviewDialog(sync, ui.plan)
        is SyncUi.Done -> ReportDialog(ui.report, onClose = sync::dismiss)
        is SyncUi.Failed -> AlertDialog(
            onDismissRequest = sync::dismiss,
            title = { Text(t.title) },
            text = { Text(failureText(ui.failure), color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = sync::dismiss) { Text(t.close) } },
        )
        else -> {}
    }
}

@Composable
private fun phaseText(phase: SyncPhase): String {
    val t = LocalStrings.current.sync
    return when (phase) {
        SyncPhase.READING -> t.phaseReading
        SyncPhase.SITE_DETAILS -> t.phaseSites
        SyncPhase.APPLYING -> t.phaseApplying
        SyncPhase.SENDING_SITES -> t.phaseSendingSites
        SyncPhase.SENDING_DIVES -> t.phaseSendingDives
        SyncPhase.PROFILES -> t.phaseProfiles
        SyncPhase.PHOTOS -> t.phasePhotos
        SyncPhase.DELETING -> t.phaseDeleting
    }
}

@Composable
private fun failureText(f: SyncFailure): String {
    val t = LocalStrings.current.sync
    return when (f) {
        SyncFailure.Unauthenticated -> t.errExpired
        is SyncFailure.RateLimited -> t.errRateLimited(f.seconds)
        SyncFailure.Network -> t.errNetwork
        SyncFailure.Insecure -> t.errInsecure
        is SyncFailure.Api -> t.errApi(f.code, f.message)
    }
}

@Composable
private fun diveLabel(date: String, siteName: String): String {
    val s = LocalStrings.current
    return listOf(if (date.isNotBlank()) s.dateText(date) else "", siteName).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = checked, role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreviewDialog(sync: SyncController, plan: SyncPlan) {
    val t = LocalStrings.current.sync
    // Rapprochements de sites : acceptés par défaut. Suppressions : refusées par défaut (rien n'est supprimé sans un geste explicite).
    val declinedLinks = remember(plan) { mutableStateListOf<String>() }
    val removeFromApp = remember(plan) { mutableStateListOf<String>() }
    val deleteFromSite = remember(plan) { mutableStateListOf<String>() }
    val choices = remember(plan) { mutableStateMapOf<String, ConflictChoice>() }
    var removePhotos by remember(plan) { mutableStateOf(true) }
    val deletions = plan.remoteDeleted.size + plan.localDeleted.size

    AlertDialog(
        onDismissRequest = sync::dismiss,
        title = { Text(t.previewTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.previewIntro, style = MaterialTheme.typography.bodySmall)
                if (plan.fullResync) Text(t.fullSyncNote, style = MaterialTheme.typography.bodySmall)
                if (plan.isEmpty) {
                    Text(t.upToDate, style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(t.toSend(plan.toSendDives, plan.sitesToSend.size))
                    Text(t.toReceive(plan.toReceive))
                    Text(t.matchedLine(plan.matched.size))
                    Text(t.deletionsLine(deletions))
                    Text(t.conflictsLine(plan.conflicts.size))
                    if (plan.profilesToSend + plan.profilesToReceive > 0) Text(t.profilesLine(plan.profilesToSend, plan.profilesToReceive), style = MaterialTheme.typography.bodySmall)
                    if (plan.photosToSend + plan.photosToReceive + plan.photosToRemove.size > 0) {
                        Text(t.photosLine(plan.photosToSend, plan.photosToReceive, plan.photosToRemove.size), style = MaterialTheme.typography.bodySmall)
                    }
                }

                val links = plan.siteLinksToConfirm
                if (links.isNotEmpty()) {
                    Section(t.siteLinksTitle, t.siteLinksHint)
                    links.forEach { r ->
                        CheckRow(r.remoteUuid !in declinedLinks, t.siteLinkRow(r.json.str("name").orEmpty(), r.link?.name.orEmpty())) { on ->
                            if (on) declinedLinks.remove(r.remoteUuid) else declinedLinks.add(r.remoteUuid)
                        }
                    }
                }
                if (plan.remoteDeleted.isNotEmpty()) {
                    Section(t.removeFromAppTitle, t.removeFromAppHint)
                    plan.remoteDeleted.forEach { (d, _) ->
                        CheckRow(d.uuid in removeFromApp, diveLabel(d.date, sync.siteName(d.siteId))) { on ->
                            if (on) removeFromApp.add(d.uuid) else removeFromApp.remove(d.uuid)
                        }
                    }
                }
                if (plan.remoteDeletedKept.isNotEmpty()) {
                    Section(t.keptTitle, t.keptHint)
                    plan.remoteDeletedKept.forEach { d -> Text(diveLabel(d.date, sync.siteName(d.siteId)), style = MaterialTheme.typography.bodyMedium) }
                }
                if (plan.localDeleted.isNotEmpty()) {
                    Section(t.deleteFromSiteTitle, t.deleteFromSiteHint)
                    plan.localDeleted.forEach { del ->
                        CheckRow(del.uuid in deleteFromSite, diveLabel(del.date, del.siteName)) { on ->
                            if (on) deleteFromSite.add(del.uuid) else deleteFromSite.remove(del.uuid)
                        }
                    }
                }
                if (plan.photosToRemove.isNotEmpty()) CheckRow(removePhotos, t.removePhotos) { removePhotos = it }
                if (plan.conflicts.isNotEmpty()) {
                    Section(t.conflictsTitle, t.conflictsHint)
                    plan.conflicts.forEach { c ->
                        val d = c.dive
                        val title = diveLabel(
                            d?.date ?: c.remote.date,
                            d?.let { sync.siteName(it.siteId) } ?: c.remote.siteJson?.str("name").orEmpty(),
                        )
                        Text(
                            title + " : " + if (c.kind == ConflictKind.BOTH_EDITED) t.conflictBoth else t.conflictDeletedHere,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        listOf(ConflictChoice.SITE to t.keepSite, ConflictChoice.APP to t.keepApp, ConflictChoice.LATER to t.decideLater).forEach { (choice, text) ->
                            val selected = (choices[c.uuid] ?: ConflictChoice.LATER) == choice
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { choices[c.uuid] = choice },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected, onClick = null)
                                Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                sync.confirm(
                    plan,
                    SyncDecisions(
                        declinedSiteLinks = declinedLinks.toSet(),
                        removeFromApp = removeFromApp.toSet(),
                        deleteFromSite = deleteFromSite.toSet(),
                        removePhotosFromSite = removePhotos,
                        conflictChoices = choices.toMap(),
                    ),
                )
            }) { Text(t.runSync) }
        },
        dismissButton = { TextButton(onClick = sync::dismiss) { Text(LocalStrings.current.cancel) } },
    )
}

@Composable
private fun Section(title: String, hint: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReportDialog(report: SyncReport, onClose: () -> Unit) {
    val t = LocalStrings.current.sync
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t.reportTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                report.failure?.let {
                    Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    Text(t.reportInterrupted, style = MaterialTheme.typography.bodySmall)
                } ?: Text(t.reportDone, style = MaterialTheme.typography.titleSmall)
                if (report.fullResync) Text(t.reportFullSync, style = MaterialTheme.typography.bodySmall)
                Text(t.reportSent(report.sentDives, report.sentSites))
                Text(t.reportReceived(report.receivedDives))
                Text(t.reportMatched(report.matchedDives))
                Text(t.reportRemoved(report.removedFromApp, report.deletedFromSite))
                Text(t.reportProfiles(report.profilesSent, report.profilesReceived))
                Text(t.reportPhotos(report.photosSent, report.photosReceived, report.photosRemoved))
                Text(t.reportConflicts(report.conflictsResolved, report.conflictsPending))
                if (report.keptDeletedOnSite > 0) Text(t.reportKept(report.keptDeletedOnSite))
                if (report.rejected.isNotEmpty()) {
                    Text(t.reportRejected(report.rejected.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    report.rejected.forEach { r ->
                        Text(r.label + " : " + r.code, style = MaterialTheme.typography.bodyMedium)
                        r.errors.forEach { (field, messages) -> Text("   $field : ${messages.joinToString(" ; ")}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t.close) } },
    )
}
