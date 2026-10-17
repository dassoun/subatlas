package com.sensitivemobility.subatlas

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
        val contacts = AndroidContactsProvider(this) {
            CompletableDeferred<Boolean>().also {
                pendingContactsPermission = it
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }.await()
        }
        handleIntent(intent)
        setContent { App(storage, location, actions, contacts) }
    }

    // Lien de plongée partagée (subatlas://dive?d=…) : transmis à l'écran principal.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString() ?: return
        IncomingLink.pending = link
    }
}
