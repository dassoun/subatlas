package com.sensitivemobility.subatlas

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocale
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
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
import platform.posix.memcpy
import kotlin.coroutines.resume

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS : navigation arrière via les boutons de l'application.
}

class IosStorage : Storage {
    private val dir: String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    /** "nobackup/sync.json" devient "nobackup_sync.json" : pas de sous-dossier ici. Pas encore exclu d'iCloud (non verifie). */
    private fun flat(name: String) = name.replace("nobackup/", "nobackup_")

    override fun read(name: String): String? {
        val path = "$dir/${flat(name)}"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun write(name: String, text: String) {
        (text as NSString).writeToFile("$dir/${flat(name)}", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
    if (isNotEmpty()) usePinned { memcpy(it.addressOf(0), bytes, length) }
}

class IosPlatformActions : PlatformActions {
    override val secretStore: SecretStore = IosSecretStore(IosStorage())

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

    /** Partage un fichier (image de plongée, fichier de plongée) : écrit dans le dossier temporaire puis feuille de partage. */
    @OptIn(ExperimentalForeignApi::class)
    override fun shareFile(name: String, mimeType: String, bytes: ByteArray): Boolean = runCatching {
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        val path = NSTemporaryDirectory() + name
        if (!data.writeToFile(path, atomically = true)) return@runCatching false
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(sheet, animated = true, completion = null)
        true
    }.getOrDefault(false)

    override suspend fun httpGet(url: String, userAgent: String): String? = suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
        request.timeoutInterval = 15.0
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode
            val text = if (error == null && data != null && status == 200L) {
                NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            } else {
                null
            }
            cont.resume(text)
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun httpGetBytes(url: String, userAgent: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
        request.timeoutInterval = 15.0
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode
            cont.resume(if (error == null && data != null && status == 200L) data.toByteArray() else null)
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    // NON VERIFIE : ce code iOS n'a pas pu etre compile ici.
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
        timeoutMillis: Int,
    ): HttpResponse = suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resumeWith(Result.failure(NetworkException("adresse illisible")))
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        request.HTTPMethod = method
        request.timeoutInterval = timeoutMillis / 1000.0
        headers.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
        if (body != null) {
            if (contentType != null) request.setValue(contentType, forHTTPHeaderField = "Content-Type")
            request.HTTPBody = if (body.isEmpty()) NSData() else body.usePinned { NSData.create(bytes = it.addressOf(0), length = body.size.toULong()) }
        }
        val task = redirectBlockingSession.dataTaskWithRequest(request) { data, response, error ->
            val http = response as? NSHTTPURLResponse
            if (error != null || http == null) {
                cont.resumeWith(Result.failure(NetworkException("réseau")))
            } else {
                val responseHeaders = HashMap<String, String>()
                http.allHeaderFields.forEach { (k, v) -> if (k != null && v != null) responseHeaders[k.toString().lowercase()] = v.toString() }
                cont.resume(HttpResponse(http.statusCode.toInt(), responseHeaders, data?.toByteArray() ?: ByteArray(0)))
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    override suspend fun openFileBytes(): ByteArray? =
        throw UnsupportedOperationException("pas encore disponible sur iOS")

    // Pas de verrouillage d'orientation sur iOS pour l'instant : le contenu est pivoté par l'appli.
    override fun setLandscape(landscape: Boolean): Boolean = false

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

/** Session qui ne suit PAS les redirections : la synchro décide elle-même (le jeton ne doit pas partir vers un autre serveur). NON VERIFIE. */
private class RedirectBlocker : NSObject(), platform.Foundation.NSURLSessionTaskDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        task: platform.Foundation.NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: platform.Foundation.NSURLRequest,
        completionHandler: (platform.Foundation.NSURLRequest?) -> Unit,
    ) = completionHandler(null)
}

private val redirectBlockingSession: NSURLSession by lazy {
    NSURLSession.sessionWithConfiguration(
        platform.Foundation.NSURLSessionConfiguration.defaultSessionConfiguration,
        delegate = RedirectBlocker(),
        delegateQueue = null,
    )
}

/**
 * Jeton d'accès à SubAtlas.com sur iOS. NON VERIFIE (iOS non compilable ici) et PAS ENCORE CHIFFRE : à remplacer par le Keychain.
 * Gardé dans un fichier du dossier de l'appli, hors de tout export.
 */
internal class IosSecretStore(private val storage: Storage) : SecretStore {
    override fun get(): String? = storage.read("nobackup/sync_token")?.takeIf { it.isNotBlank() }
    override fun set(token: String) = storage.write("nobackup/sync_token", token)
    override fun clear() = storage.write("nobackup/sync_token", "")
}
