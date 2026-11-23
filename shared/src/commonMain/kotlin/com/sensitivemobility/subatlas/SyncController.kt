package com.sensitivemobility.subatlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val DEFAULT_SYNC_SERVER = "https://subatlas.com"

/** Ce que l'écran de synchronisation montre à un instant. */
internal sealed interface SyncUi {
    data object Idle : SyncUi
    data object Connecting : SyncUi
    data class Working(val progress: SyncProgress) : SyncUi
    data class Preview(val plan: SyncPlan) : SyncUi
    data class Done(val report: SyncReport) : SyncUi
    data class Failed(val failure: SyncFailure) : SyncUi
}

enum class ConnectError { INVALID_SERVER, INSECURE, INVALID_CODE, NETWORK, RATE_LIMITED, OTHER }

/**
 * Connexion au compte SubAtlas.com et synchronisation, déclenchées par l'utilisateur (jamais en arrière-plan, jamais au démarrage).
 * L'appli fonctionne exactement comme avant tant qu'aucun compte n'est connecté.
 */
internal class SyncController(
    private val state: AppState,
    private val actions: PlatformActions,
    storage: Storage,
    private val scope: CoroutineScope,
    private val french: () -> Boolean,
) {
    private val store = SyncStore(storage)
    private val secrets: SecretStore? = actions.secretStore

    /** La synchronisation n'est proposée que si la plateforme sait garder le jeton à l'abri et faire des requêtes. */
    val available: Boolean get() = secrets != null

    var sync by mutableStateOf(store.state)
        private set
    var ui by mutableStateOf<SyncUi>(SyncUi.Idle)
        private set
    var serverDraft by mutableStateOf(store.state.server.ifBlank { DEFAULT_SYNC_SERVER })
    var codeDraft by mutableStateOf("")
    var connectError by mutableStateOf<ConnectError?>(null)
        private set
    var connectMessage by mutableStateOf("")
        private set
    var linkReceived by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set

    val connected: Boolean get() = sync.connected && secrets?.get() != null
    val debugBuild: Boolean get() = actions.allowsCleartextHttp

    private var job: Job? = null
    private var waitSeconds by mutableStateOf(0)

    private fun refresh() { sync = store.state }

    /** Nom du site local d'identifiant [id] (pour l'aperçu). */
    fun siteName(id: Long): String = state.site(id)?.name.orEmpty()

    /** Lien `subatlas://connect?server=…&code=…` (QR du site) : préremplit l'adresse et le code, sans rien envoyer. */
    fun prefillFromLink(link: String): Boolean {
        val (server, code) = parseConnectLink(link) ?: return false
        if (server.isNotBlank()) serverDraft = server
        codeDraft = code
        linkReceived = true
        connectError = null
        return true
    }

    fun connect() {
        val secrets = secrets ?: return
        val server = normalizeServer(serverDraft)
        if (server == null) { connectError = ConnectError.INVALID_SERVER; return }
        val code = codeDraft.trim()
        if (code.isEmpty() || connecting) return
        connectError = null
        connecting = true
        scope.launch {
            try {
                val deviceId = store.deviceId()
                val anonymous = ApiClient(actions, server, deviceId, token = { null })
                val exchange = anonymous.send(
                    "POST", "/auth/token/exchange",
                    body = buildJsonObject { put("code", code); put("device_name", actions.deviceName()); put("device_id", deviceId) },
                    authenticated = false,
                )
                val token = (exchange.json as? JsonObject)?.str("token") ?: throw ApiError(200, "bad_response", "pas de jeton")
                secrets.set(token)
                val me = ApiClient(actions, server, deviceId, token = { token }).get("/me")
                val username = (me.data as? JsonObject)?.str("username") ?: (me.data as? JsonObject)?.str("name").orEmpty().ifBlank { "?" }
                store.update { st ->
                    // Un autre compte (ou un autre serveur) : on repart de zéro ; le même compte reprend où il en était.
                    val sameLink = st.lastAccount.isBlank() || (st.lastAccount == username && st.server == server)
                    val base = if (sameLink) st else SyncState(deviceId = st.deviceId)
                    base.copy(server = server, account = username, lastAccount = username)
                }
                codeDraft = ""
                linkReceived = false
                refresh()
            } catch (e: ApiError) {
                secrets.clear()
                connectError = when (e.code) {
                    "validation_failed" -> ConnectError.INVALID_CODE
                    "rate_limited" -> ConnectError.RATE_LIMITED
                    else -> ConnectError.OTHER
                }
                connectMessage = e.message
            } catch (e: NetworkException) {
                connectError = ConnectError.NETWORK
            } catch (e: InsecureServerException) {
                connectError = ConnectError.INSECURE
            } finally {
                connecting = false
            }
        }
    }

    /** Révoque le jeton sur le site (si possible), puis l'efface de l'appli dans tous les cas. */
    fun disconnect() {
        val secrets = secrets ?: return
        val server = store.state.server
        val token = secrets.get()
        job?.cancel()
        ui = SyncUi.Idle
        // Déconnecté d'abord : le bouton réagit tout de suite, même sans réseau.
        secrets.clear()
        store.update { it.copy(account = "") }
        refresh()
        if (token != null && server.isNotBlank()) {
            scope.launch {
                runCatching { ApiClient(actions, server, store.deviceId(), token = { token }).send("DELETE", "/auth/token") }
            }
        }
    }

    private fun onUnauthenticated() {
        secrets?.clear()
        store.update { it.copy(account = "") }
        refresh()
    }

    private fun client(): ApiClient {
        val st = store.state
        return ApiClient(
            actions, st.server, store.deviceId(), token = { secrets?.get() },
            onUnauthenticated = ::onUnauthenticated,
            onWait = { seconds -> (ui as? SyncUi.Working)?.let { ui = SyncUi.Working(SyncProgress(it.progress.phase, it.progress.done, it.progress.total, seconds)) } },
        )
    }

    private fun engine(): SyncEngine = SyncEngine(state, store, client(), french()) { p -> ui = SyncUi.Working(p) }

    /** « Synchroniser » : lit le site (sans rien modifier), puis propose l'aperçu à confirmer. */
    fun start() {
        if (!connected || ui is SyncUi.Working) return
        ui = SyncUi.Working(SyncProgress(SyncPhase.READING))
        job = scope.launch {
            try {
                ui = SyncUi.Preview(engine().prepare())
            } catch (e: CancellationException) {
                ui = SyncUi.Idle
                throw e
            } catch (e: Exception) {
                ui = SyncUi.Failed(failureOf(e))
                refresh()
            }
        }
    }

    internal fun confirm(plan: SyncPlan, decisions: SyncDecisions) {
        ui = SyncUi.Working(SyncProgress(SyncPhase.APPLYING))
        job = scope.launch {
            try {
                val report = engine().execute(plan, decisions)
                ui = SyncUi.Done(report)
            } catch (e: CancellationException) {
                ui = SyncUi.Idle
                throw e
            } finally {
                refresh()
            }
        }
    }

    /** Interrompt la synchro en cours ; ce qui est déjà confirmé par le site est gardé, la suite reprendra sans doublon. */
    fun cancel() {
        job?.cancel()
        ui = SyncUi.Idle
        refresh()
    }

    fun dismiss() { ui = SyncUi.Idle }

    private fun failureOf(e: Exception): SyncFailure = when (e) {
        is ApiError -> when (e.code) {
            "unauthenticated" -> SyncFailure.Unauthenticated
            "rate_limited" -> SyncFailure.RateLimited(e.retryAfterSeconds ?: 60)
            else -> SyncFailure.Api(e.code, e.message)
        }
        is NetworkException -> SyncFailure.Network
        is InsecureServerException -> SyncFailure.Insecure
        else -> SyncFailure.Api("error", e.message ?: e.toString())
    }
}

/** Adresse du serveur saisie -> adresse propre (« https://hôte[:port][/chemin] » sans barre finale), ou `null` si elle n'est pas valable. */
internal fun normalizeServer(text: String): String? {
    var t = text.trim().trimEnd('/')
    if (t.isEmpty()) return null
    if ("://" !in t) t = "https://$t"
    val lower = t.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
    val host = t.substringAfter("://").substringBefore('/')
    if (host.isBlank() || host.any { it.isWhitespace() }) return null
    return t
}

/** `subatlas://connect?server=<url>&code=<code>` -> (serveur, code) ; `null` si ce n'est pas un lien de connexion. */
internal fun parseConnectLink(link: String): Pair<String, String>? {
    if (!link.startsWith("subatlas://connect", ignoreCase = true)) return null
    val params = link.substringAfter('?', "").split('&').filter { it.contains('=') }
        .associate { it.substringBefore('=') to urlDecode(it.substringAfter('=')) }
    val code = params["code"]?.trim().orEmpty()
    if (code.isEmpty()) return null
    return params["server"].orEmpty().trim() to code
}

internal fun urlDecode(text: String): String {
    val out = ArrayList<Byte>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length && text.substring(i + 1, i + 3).toIntOrNull(16) != null) {
            out.add(text.substring(i + 1, i + 3).toInt(16).toByte())
            i += 3
        } else {
            if (c == '+') out.add(' '.code.toByte()) else c.toString().encodeToByteArray().forEach { out.add(it) }
            i++
        }
    }
    return out.toByteArray().decodeToString()
}
