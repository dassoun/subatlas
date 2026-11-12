package com.sensitivemobility.subatlas

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
        }

    private var pendingContactsPermission: CompletableDeferred<Boolean>? = null

    private val contactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingContactsPermission?.complete(granted)
        }

    private var pendingPickPhone: CompletableDeferred<Uri?>? = null

    // Sélecteur de contacts du système : renvoie le numéro choisi, sans demander l'accès à tout le répertoire.
    private val pickPhoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingPickPhone?.complete(if (result.resultCode == RESULT_OK) result.data?.data else null)
        }

    private var pendingSave: CompletableDeferred<Uri?>? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { pendingSave?.complete(it) }
    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingOpen?.complete(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Doit précéder super.onCreate : bascule du thème de démarrage vers le thème normal.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val storage = AndroidStorage(applicationContext)
        val actions = AndroidPlatformActions(
            this,
            pickSaveUri = { name ->
                CompletableDeferred<Uri?>().also { pendingSave = it; saveLauncher.launch(name) }.await()
            },
            pickOpenUri = {
                CompletableDeferred<Uri?>().also { pendingOpen = it; openLauncher.launch(arrayOf("*/*")) }.await()
            },
        )
        val location = AndroidLocationProvider(this) {
            CompletableDeferred<Boolean>().also {
                pendingPermission = it
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }.await()
        }
        val contacts = AndroidContactsProvider(
            this,
            requestPermission = {
                CompletableDeferred<Boolean>().also {
                    pendingContactsPermission = it
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }.await()
            },
            pickPhone = {
                CompletableDeferred<Uri?>().also {
                    pendingPickPhone = it
                    pickPhoneLauncher.launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                }.await()
            },
        )
        handleIntent(intent)
        setContent { App(storage, location, actions, contacts) }
        excludeEdgesFromBackGesture()
    }

    /**
     * Le glissé d'une fiche à l'autre part souvent du bord de l'écran, où le système le prendrait pour le geste « retour ».
     * Android permet de réserver au plus 200 dp de hauteur par bord : on les place au milieu de l'écran.
     */
    private fun excludeEdgesFromBackGesture() {
        if (Build.VERSION.SDK_INT < 29) return
        val decor = window.decorView
        decor.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val d = v.resources.displayMetrics.density
            val edge = (32 * d).toInt()
            val half = (100 * d).toInt()
            val mid = (t + b) / 2
            v.systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, mid - half, edge, mid + half),
                android.graphics.Rect(r - l - edge, mid - half, r - l, mid + half),
            )
        }
    }

    // Lien de plongée partagée (subatlas://dive?d=…) ou fichier reçu d'une autre appli : transmis à l'écran principal.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "subatlas") IncomingLink.pending = data.toString() else readIncomingFile(data)
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (uri != null) readIncomingFile(uri, keepCopy = true)
            }
        }
    }

    /**
     * Lit le fichier reçu hors du fil principal, puis le transmet à l'écran principal (qui le reconnaît ou le refuse).
     * Avec [keepCopy], une base SQLite reçue (export Shearwater) est aussi sauvegardée dans Téléchargements.
     */
    private fun readIncomingFile(uri: Uri, keepCopy: Boolean = false) {
        Thread {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) {
                if (keepCopy && isSqlite(bytes)) {
                    val saved = runCatching { saveToDownloads(uri, bytes) }.getOrDefault(false)
                    if (saved) runOnUiThread { Toast.makeText(this, "Export enregistré dans Téléchargements", Toast.LENGTH_SHORT).show() }
                }
                runOnUiThread { IncomingFile.pending = bytes }
            }
        }.start()
    }

    private fun isSqlite(bytes: ByteArray): Boolean {
        val magic = "SQLite format 3".encodeToByteArray()
        return bytes.size > magic.size && magic.indices.all { bytes[it] == magic[it] }
    }

    /** Enregistre [bytes] dans le dossier Téléchargements (Android 10+ ; avant, l'accès demanderait une permission de stockage). */
    private fun saveToDownloads(source: Uri, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val original = contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val base = original?.takeIf { it.isNotBlank() } ?: ("shearwater-" + System.currentTimeMillis() + ".db")
        val name = if (base.contains('.')) base else "$base.db"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(target)?.use { it.write(bytes) } ?: return false
        contentResolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return true
    }
}
