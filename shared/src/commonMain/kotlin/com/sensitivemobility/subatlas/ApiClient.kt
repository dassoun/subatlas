package com.sensitivemobility.subatlas

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Réponse d'erreur de l'API (`{ message, code, errors }`). [code] : unauthenticated (401), forbidden (403), not_found (404),
 * validation_failed (422), gone (410), rate_limited (429), ou « http_<code> » pour le reste. Le message ne contient jamais le jeton.
 */
class ApiError(
    val status: Int,
    val code: String,
    override val message: String,
    val errors: Map<String, List<String>> = emptyMap(),
    /** Secondes à attendre avant de réessayer (429). */
    val retryAfterSeconds: Int? = null,
) : Exception(message)

/** L'adresse du serveur n'est pas acceptable (HTTP en clair hors version de développement, ou adresse illisible). */
class InsecureServerException(message: String) : Exception(message)

/** Réponse réussie (code < 400). [url] est l'adresse finale, après les redirections suivies. */
class ApiResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray, val url: String) {
    /** Corps JSON complet, `null` s'il est vide ou n'est pas du JSON. */
    val json: JsonElement? by lazy {
        if (body.isEmpty()) null else runCatching { ApiClient.json.parseToJsonElement(body.decodeToString()) }.getOrNull()
    }

    /** Le contenu de l'enveloppe `{ "data": ... }`. */
    val data: JsonElement? get() = (json as? JsonObject)?.get("data")

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Client de l'API `{serveur}/api/v1`. Aucune requête ne part sans qu'un appelant (une action de l'utilisateur) l'ait demandée.
 *
 * - Chaque requête porte `Authorization: Bearer <jeton>` et `X-Device-Id`. Le jeton n'est écrit nulle part : ni journal, ni message d'erreur.
 * - Un 429 est attendu (`Retry-After`, 60 s au plus) puis rejoué, trois fois au plus ; au-delà, l'erreur est remontée.
 * - Les redirections d'un GET sont suivies seulement vers le même serveur : le jeton ne part jamais ailleurs.
 * - Un 401 appelle [onUnauthenticated] (jeton révoqué ou expiré : l'appli repasse en « déconnecté ») puis lève l'erreur.
 *
 * @param token fournit le jeton courant (lu à chaque requête) ; `null` : requête sans authentification.
 * @param onWait prévenu quand une attente de [Retry-After] commence (pour la progression), avec le nombre de secondes.
 */
class ApiClient(
    private val platform: PlatformActions,
    server: String,
    private val deviceId: String,
    private val token: () -> String?,
    private val onUnauthenticated: () -> Unit = {},
    private val onWait: (Int) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val server = server.trim().trimEnd('/')
    private val base = "${this.server}/api/v1"

    /** Requête JSON (ou sans corps). [path] : « /sync/changes » ; [query] : paramètres, encodés ici. */
    suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        authenticated: Boolean = true,
        timeoutMillis: Int = 20_000,
    ): ApiResponse = execute(
        method, base + path + queryString(query), authenticated,
        body?.let { json.encodeToString(JsonElement.serializer(), it).encodeToByteArray() },
        if (body != null) "application/json" else null,
        timeoutMillis,
    )

    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()) = send("GET", path, query)

    suspend fun postMultipart(path: String, body: MultipartBody, timeoutMillis: Int = 60_000): ApiResponse =
        execute("POST", base + path, true, body.bytes, body.contentType, timeoutMillis)

    /**
     * GET d'un fichier (photo) à une adresse absolue donnée par le site. Le jeton n'est joint que si l'adresse est celle du
     * serveur connecté.
     */
    suspend fun getBytes(url: String, timeoutMillis: Int = 60_000): ApiResponse =
        onServer(url).let { execute("GET", it, authenticated = origin(it) == origin(server), null, null, timeoutMillis, accept = "*/*") }

    /**
     * Les adresses données par le site (photos, redirections) sont construites sur SON adresse publique, parfois différente de celle
     * que l'appli utilise (en développement : « localhost » pour le site, « 10.0.2.2 » pour l'émulateur). Une adresse de l'API du
     * site est donc toujours relue sur le serveur auquel l'appli est connectée ; toute autre adresse reste telle quelle.
     */
    private fun onServer(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val path = url.substring(url.indexOf('/', schemeEnd + 3).let { if (it < 0) url.length else it })
        return if (path.startsWith("/api/v1/")) server + path else url
    }

    private suspend fun execute(
        method: String,
        startUrl: String,
        authenticated: Boolean,
        body: ByteArray?,
        contentType: String?,
        timeoutMillis: Int,
        accept: String = "application/json",
    ): ApiResponse {
        var url = startUrl
        var waits = 0
        var redirects = 0
        while (true) {
            if (url.startsWith("http://", ignoreCase = true) && !platform.allowsCleartextHttp) {
                throw InsecureServerException("HTTPS obligatoire")
            }
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                throw InsecureServerException("adresse illisible")
            }
            val headers = LinkedHashMap<String, String>()
            headers["Accept"] = accept
            val sameServer = origin(url) == origin(server)
            if (sameServer) headers["X-Device-Id"] = deviceId
            // Le jeton ne part que vers le serveur connecté.
            val bearer = if (authenticated && sameServer) token() else null
            if (bearer != null) headers["Authorization"] = "Bearer $bearer"

            val response = platform.httpRequest(method, url, headers, body, contentType, timeoutMillis)
            val status = response.status
            when {
                status in 301..308 && status != 304 && method == "GET" -> {
                    val target = resolve(url, response.header("location"))?.let(::onServer) ?: throw error(response)
                    if (origin(target) != origin(server) || ++redirects > 5) throw ApiError(status, "redirect", "redirection refusée")
                    url = target
                }
                status == 429 && waits < 3 -> {
                    val seconds = response.header("retry-after")?.trim()?.toIntOrNull() ?: 5
                    if (seconds > MAX_WAIT_SECONDS) throw error(response)
                    waits++
                    onWait(seconds)
                    sleep(seconds * 1000L)
                }
                status >= 400 -> {
                    if (status == 401 && bearer != null) onUnauthenticated()
                    throw error(response)
                }
                else -> return ApiResponse(status, response.headers, response.body, url)
            }
        }
    }

    private fun error(response: HttpResponse): ApiError {
        val obj = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
        val status = response.status
        val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull ?: when (status) {
            401 -> "unauthenticated"
            403 -> "forbidden"
            404 -> "not_found"
            410 -> "gone"
            422 -> "validation_failed"
            429 -> "rate_limited"
            else -> "http_$status"
        }
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: "HTTP $status"
        val errors = (obj?.get("errors") as? JsonObject)?.mapValues { (_, v) ->
            (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: listOfNotNull((v as? JsonPrimitive)?.contentOrNull)
        }.orEmpty()
        return ApiError(status, code, message, errors, response.header("retry-after")?.trim()?.toIntOrNull())
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Attente maximale d'un `Retry-After` : au-delà, la synchro s'arrête et dit quand réessayer. */
        const val MAX_WAIT_SECONDS = 60

        internal fun queryString(query: List<Pair<String, String>>): String =
            if (query.isEmpty()) "" else query.joinToString("&", prefix = "?") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

        /** « https://hote:port » d'une adresse (sans le chemin). */
        internal fun origin(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url.lowercase()
            val pathStart = url.indexOf('/', schemeEnd + 3).let { if (it < 0) url.length else it }
            return url.substring(0, pathStart).lowercase()
        }

        /** Adresse absolue d'un en-tête `Location` (absolue ou commençant par « / »). */
        internal fun resolve(from: String, location: String?): String? = when {
            location.isNullOrBlank() -> null
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("/") -> origin(from) + location
            else -> null
        }
    }
}
