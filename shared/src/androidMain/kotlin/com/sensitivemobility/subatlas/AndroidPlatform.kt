package com.sensitivemobility.subatlas

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler as ActivityBackHandler
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = ActivityBackHandler(enabled, onBack)

class AndroidStorage(context: Context) : Storage {
    private val dir = context.filesDir
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    override fun write(name: String, text: String) = File(dir, name).writeText(text)
}

/**
 * @param pickSaveUri ouvre le sélecteur « Enregistrer sous » du système (fourni par l'Activity) ; `null` si annulé.
 * @param pickOpenUri ouvre le sélecteur de fichier du système (fourni par l'Activity) ; `null` si annulé.
 */
class AndroidPlatformActions(
    private val context: Context,
    private val pickSaveUri: suspend (suggestedName: String) -> Uri?,
    private val pickOpenUri: suspend () -> Uri?,
) : PlatformActions {
    override fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun systemLanguage(): String = java.util.Locale.getDefault().language

    override suspend fun saveTextFile(suggestedName: String, text: String): Boolean {
        val uri = pickSaveUri(suggestedName) ?: return false
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("impossible d'écrire le fichier")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        return true
    }

    override suspend fun openTextFile(): String? {
        val uri = pickOpenUri() ?: return null
        return withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: error("impossible de lire le fichier")
            input.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    override fun openMap(latitude: Double, longitude: Double, label: String) {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/**
 * @param requestPermission suspend: demande ACCESS_FINE_LOCATION à l'utilisateur
 *        (fournie par l'Activity) et renvoie true si accordée.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val requestPermission: suspend () -> Boolean,
) : LocationProvider {

    override suspend fun currentLocation(): LocationResult {
        if (!hasPermission() && !requestPermission()) return LocationResult.PermissionDenied
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return LocationResult.Unavailable
        val location = fetch(manager, provider)
        return if (location != null) LocationResult.Success(location.latitude, location.longitude)
        else LocationResult.Unavailable
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun fetch(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) {
                    if (cont.isActive) cont.resume(it)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }
}

/**
 * Recherche dans le répertoire. La permission READ_CONTACTS n'est demandée qu'à la première recherche.
 *
 * @param requestPermission suspend : demande READ_CONTACTS (fournie par l'Activity) et renvoie true si accordée.
 */
class AndroidContactsProvider(
    private val context: Context,
    private val requestPermission: suspend () -> Boolean,
) : ContactsProvider {

    override suspend fun search(query: String): ContactSearch {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !requestPermission()) return ContactSearch.PermissionDenied
        return ContactSearch.Found(withContext(Dispatchers.IO) { find(query.trim()) })
    }

    private fun find(query: String): List<PhoneContact> {
        if (query.isEmpty()) return emptyList()
        val resolver = context.contentResolver
        val out = mutableListOf<PhoneContact>()
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 6) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val phone = firstValue(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    id,
                )
                val email = firstValue(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    id,
                )
                out += PhoneContact(name, phone, email)
            }
        }
        return out
    }

    private fun firstValue(uri: Uri, column: String, idColumn: String, id: String): String =
        context.contentResolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(id), null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }.orEmpty()
}
