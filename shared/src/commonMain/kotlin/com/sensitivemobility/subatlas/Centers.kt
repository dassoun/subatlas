package com.sensitivemobility.subatlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------- Liens de contact

/** Lien à ouvrir pour un site web saisi à la main : on ajoute `https://` quand le schéma est omis. */
internal fun DiveCenter.websiteLink(): String = website.trim().let { if ("://" in it) it else "https://$it" }

internal fun DiveCenter.phoneLink(): String = "tel:" + phone.filter { it.isDigit() || it == '+' }

internal fun DiveCenter.emailLink(): String = "mailto:" + email.trim()

/** Un champ vide est accepté (l'e-mail est facultatif) ; sinon : quelque chose@domaine.extension, sans espace. */
internal fun isValidOptionalEmail(raw: String): Boolean =
    raw.isBlank() || Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(raw.trim())

// ------------------------------------------------------------- Coordonnées

/**
 * Champs latitude / longitude et bouton « utiliser ma position GPS », communs aux formulaires de site et de centre.
 */
@Composable
internal fun GpsFields(
    lat: String,
    lon: String,
    onLat: (String) -> Unit,
    onLon: (String) -> Unit,
    location: LocationProvider,
) {
    val s = LocalStrings.current
    var message by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !locating,
            onClick = {
                locating = true
                message = null
                scope.launch {
                    when (val r = location.currentLocation()) {
                        is LocationResult.Success -> {
                            onLat(r.latitude.toString())
                            onLon(r.longitude.toString())
                        }
                        LocationResult.PermissionDenied -> message = s.permissionDenied
                        LocationResult.Unavailable -> message = s.locationUnavailable
                    }
                    locating = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (locating) s.locating else s.useMyPosition) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                lat, onLat, label = { Text(s.latitude) }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                lon, onLon, label = { Text(s.longitude) }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f),
            )
        }
    }
}

// ------------------------------------------------------ Centres de plongée

@Composable
internal fun CenterList(state: AppState, pad: PaddingValues, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize().padding(pad)) {
        if (state.centers.isEmpty()) {
            Text(s.noCenters, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.centers, key = { it.id }) { center ->
                Card(
                    onClick = { onOpen(center.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(center.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatCoords(center.latitude, center.longitude), style = MaterialTheme.typography.bodySmall)
                        val contact = listOf(center.phone, center.email).filter { it.isNotBlank() }.joinToString(" · ")
                        if (contact.isNotEmpty()) Text(contact, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(s.addCenter) }
    }
}

@Composable
internal fun CenterDetailView(
    center: DiveCenter,
    pad: PaddingValues,
    actions: PlatformActions,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(center.name, style = MaterialTheme.typography.headlineSmall)
        Text(s.position(formatCoords(center.latitude, center.longitude)))
        if (center.phone.isNotBlank()) Text(s.phoneLine(center.phone))
        if (center.email.isNotBlank()) Text(s.emailLine(center.email))
        if (center.website.isNotBlank()) Text(s.websiteLine(center.website))
        if (center.notes.isNotBlank()) Text(s.notesLine(center.notes))
        Button(
            onClick = { actions.openMap(center.latitude, center.longitude, center.name) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.openInMaps) }
        if (center.phone.isNotBlank()) {
            OutlinedButton(onClick = { actions.openLink(center.phoneLink()) }, modifier = Modifier.fillMaxWidth()) {
                Text(s.call)
            }
        }
        if (center.email.isNotBlank()) {
            OutlinedButton(onClick = { actions.openLink(center.emailLink()) }, modifier = Modifier.fillMaxWidth()) {
                Text(s.sendEmail)
            }
        }
        if (center.website.isNotBlank()) {
            OutlinedButton(onClick = { actions.openLink(center.websiteLink()) }, modifier = Modifier.fillMaxWidth()) {
                Text(s.openWebsite)
            }
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(s.edit) }
        TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(s.delete) }
    }
    if (confirm) {
        ConfirmDialog(
            s.confirmDelete(center.name),
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
internal fun CenterForm(
    state: AppState,
    existing: DiveCenter?,
    location: LocationProvider,
    pad: PaddingValues,
    onDone: () -> Unit,
) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var lat by remember { mutableStateOf(existing?.latitude?.toString() ?: "") }
    var lon by remember { mutableStateOf(existing?.longitude?.toString() ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    var website by remember { mutableStateOf(existing?.website ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val latVal = lat.replace(',', '.').toDoubleOrNull()
    val lonVal = lon.replace(',', '.').toDoubleOrNull()
    val emailOk = isValidOptionalEmail(email)
    val valid = name.isNotBlank() && emailOk &&
        latVal != null && latVal in -90.0..90.0 &&
        lonVal != null && lonVal in -180.0..180.0

    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            name, { name = it }, label = { Text(s.centerName) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GpsFields(lat, lon, { lat = it }, { lon = it }, location)
        OutlinedTextField(
            phone, { phone = it }, label = { Text(s.phone) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            email, { email = it }, label = { Text(s.email) }, singleLine = true,
            isError = !emailOk,
            supportingText = if (emailOk) null else ({ Text(s.invalidEmail) }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            website, { website = it }, label = { Text(s.website) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            notes, { notes = it }, label = { Text(s.centerNotesField) }, minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = valid,
            onClick = {
                state.saveCenter(
                    DiveCenter(
                        id = existing?.id ?: state.newId(),
                        name = name.trim(),
                        latitude = latVal!!,
                        longitude = lonVal!!,
                        phone = phone.trim(),
                        email = email.trim(),
                        website = website.trim(),
                        notes = notes.trim(),
                    ),
                )
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.save) }
    }
}
