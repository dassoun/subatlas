package com.sensitivemobility.subatlas

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.Contacts.CNContact
import platform.Contacts.CNContactEmailAddressesKey
import platform.Contacts.CNContactFormatter
import platform.Contacts.CNContactFormatterStyleFullName
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityTypeContacts
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.create
import platform.Foundation.preferredLanguages
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import kotlin.coroutines.resume

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS : navigation arrière via les boutons de l'application.
}

class IosStorage : Storage {
    private val dir: String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    override fun read(name: String): String? {
        val path = "$dir/$name"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun write(name: String, text: String) {
        (text as NSString).writeToFile("$dir/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}

class IosPlatformActions : PlatformActions {
    override fun openLink(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, emptyMap<Any?, Any?>(), null)
    }

    override fun systemLanguage(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"

    // Export et import de fichiers : pas encore implémentés sur iOS (UIDocumentPicker / feuille de partage).
    override suspend fun saveTextFile(suggestedName: String, text: String): Boolean =
        throw UnsupportedOperationException("pas encore disponible sur iOS")

    override suspend fun openTextFile(): String? =
        throw UnsupportedOperationException("pas encore disponible sur iOS")

    override fun shareText(text: String) {
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(sheet, animated = true, completion = null)
    }

    override fun openMap(latitude: Double, longitude: Double, label: String) {
        val url = NSURL.URLWithString("http://maps.apple.com/?ll=$latitude,$longitude&q=${label.replace(" ", "%20")}")
            ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosLocationProvider : LocationProvider {
    private val manager = CLLocationManager()
    private var pending: ((LocationResult) -> Unit)? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways ->
                    manager.requestLocation()
                kCLAuthorizationStatusNotDetermined -> Unit
                else -> finish(LocationResult.PermissionDenied)
            }
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val loc = didUpdateLocations.lastOrNull() as? CLLocation
            if (loc == null) finish(LocationResult.Unavailable)
            else loc.coordinate.useContents { finish(LocationResult.Success(latitude, longitude)) }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            finish(LocationResult.Unavailable)
        }
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = platform.CoreLocation.kCLLocationAccuracyBest
    }

    private fun finish(result: LocationResult) {
        pending?.invoke(result)
        pending = null
    }

    override suspend fun currentLocation(): LocationResult = suspendCancellableCoroutine { cont ->
        pending = { cont.resume(it) }
        cont.invokeOnCancellation { pending = null }
        when (manager.authorizationStatus) {
            kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways ->
                manager.requestLocation()
            else -> finish(LocationResult.PermissionDenied)
        }
    }
}

/** Recherche dans le répertoire (framework Contacts). La permission n'est demandée qu'à la première recherche. */
class IosContactsProvider : ContactsProvider {
    private val store = CNContactStore()

    override suspend fun search(query: String): ContactSearch {
        val granted = when (CNContactStore.authorizationStatusForEntityType(CNEntityTypeContacts)) {
            CNAuthorizationStatusAuthorized -> true
            CNAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { cont ->
                store.requestAccessForEntityType(CNEntityTypeContacts) { ok, _ -> cont.resume(ok) }
            }
            else -> false
        }
        if (!granted) return ContactSearch.PermissionDenied

        val keys = listOf(
            CNContactFormatter.descriptorForRequiredKeysForStyle(CNContactFormatterStyleFullName),
            CNContactPhoneNumbersKey,
            CNContactEmailAddressesKey,
        )
        val found = store.unifiedContactsMatchingPredicate(CNContact.predicateForContactsMatchingName(query), keys, null)
            .orEmpty()
            .filterIsInstance<CNContact>()
            .take(6)
            .mapNotNull { c ->
                val name = CNContactFormatter.stringFromContact(c, CNContactFormatterStyleFullName) ?: return@mapNotNull null
                val phone = ((c.phoneNumbers.firstOrNull() as? CNLabeledValue)?.value as? CNPhoneNumber)?.stringValue.orEmpty()
                val email = ((c.emailAddresses.firstOrNull() as? CNLabeledValue)?.value as? String).orEmpty()
                PhoneContact(name, phone, email)
            }
        return ContactSearch.Found(found)
    }
}

/** Point d'entrée appelé depuis Swift (ContentView). */
fun MainViewController() = ComposeUIViewController {
    App(
        storage = IosStorage(),
        location = IosLocationProvider(),
        actions = IosPlatformActions(),
        contacts = IosContactsProvider(),
    )
}
