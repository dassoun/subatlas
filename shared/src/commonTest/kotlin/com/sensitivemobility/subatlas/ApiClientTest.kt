package com.sensitivemobility.subatlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordedRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?, val contentType: String?)

/** Plateforme factice : rend les réponses prévues dans l'ordre et note les requêtes reçues. */
class FakeHttpPlatform(val cleartext: Boolean = true) : PlatformActions {
    val requests = ArrayList<RecordedRequest>()
    val script = ArrayDeque<() -> HttpResponse>()

    fun reply(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        script.addLast { HttpResponse(status, headers.mapKeys { it.key.lowercase() }, body.encodeToByteArray()) }
    }

    fun fail() { script.addLast { throw NetworkException("coupé") } }

    override val allowsCleartextHttp: Boolean get() = cleartext

    override suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, timeoutMillis: Int): HttpResponse {
        requests += RecordedRequest(method, url, headers, body, contentType)
        return (script.removeFirstOrNull() ?: error("réponse non prévue pour $method $url"))()
    }

    override fun openMap(latitude: Double, longitude: Double, label: String) {}
    override fun setLandscape(landscape: Boolean) = false
    override fun shareText(text: String) {}
    override fun openLink(url: String) {}
    override fun systemLanguage() = "fr"
    override suspend fun saveTextFile(suggestedName: String, text: String) = false
    override suspend fun openTextFile(): String? = null
    override suspend fun openFileBytes(): ByteArray? = null
}

class ApiClientTest {
    private val secret = "tok-SECRET-123"

    private fun client(
        p: FakeHttpPlatform,
        server: String = "https://subatlas.test/",
        unauthenticated: () -> Unit = {},
        waits: MutableList<Long> = ArrayList(),
        onWait: (Int) -> Unit = {},
    ) = ApiClient(p, server, "device-1", { secret }, unauthenticated, onWait, sleep = { waits += it })

    private fun fake(cleartext: Boolean = true) = FakeHttpPlatform(cleartext)

    @Test
    fun sendsAuthHeadersAndUnwrapsEnvelope() = runTest {
        val p = fake()
        p.reply(200, """{"data":{"name":"julien"}}""")
        val r = ApiClient(p, "https://subatlas.test/", "device-1", { secret }).get("/me")
        val req = p.requests.single()
        assertEquals("GET", req.method)
        assertEquals("https://subatlas.test/api/v1/me", req.url)
        assertEquals("Bearer $secret", req.headers["Authorization"])
        assertEquals("device-1", req.headers["X-Device-Id"])
        assertEquals("application/json", req.headers["Accept"])
        assertEquals("julien", (r.data as JsonObject)["name"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun queryIsEncoded() = runTest {
        val p = fake()
        p.reply(200, """{"data":{}}""")
        ApiClient(p, "https://s.test", "d", { secret }).get("/sync/changes", listOf("since" to "a b+c==", "limit" to "200"))
        assertEquals("https://s.test/api/v1/sync/changes?since=a%20b%2Bc%3D%3D&limit=200", p.requests.single().url)
    }

    @Test
    fun jsonBodyAndPatchMethodArePassedToPlatform() = runTest {
        val p = fake()
        p.reply(200, """{"data":{}}""")
        ApiClient(p, "https://s.test", "d", { secret }).send("PATCH", "/dives/u1", body = buildJsonObject { put("max_depth_m", 20.5) })
        val req = p.requests.single()
        assertEquals("PATCH", req.method)
        assertEquals("application/json", req.contentType)
        assertEquals("""{"max_depth_m":20.5}""", req.body!!.decodeToString())
    }

    @Test
    fun errorEnvelopeIsParsedWithFieldErrors() = runTest {
        val p = fake()
        p.reply(422, """{"message":"Invalid","code":"validation_failed","errors":{"started_at":["mauvais format"]}}""")
        val e = assertFailsWith<ApiError> { client(p).get("/x") }
        assertEquals(422, e.status)
        assertEquals("validation_failed", e.code)
        assertEquals(listOf("mauvais format"), e.errors["started_at"])
    }

    @Test
    fun errorCodeFallsBackOnStatus() = runTest {
        val p = fake()
        p.reply(410, "<html>")
        assertEquals("gone", assertFailsWith<ApiError> { client(p).get("/x") }.code)
    }

    @Test
    fun unauthenticatedNotifiesAndNeverLeaksToken() = runTest {
        val p = fake()
        p.reply(401, """{"message":"Unauthenticated.","code":"unauthenticated"}""")
        var called = 0
        val e = assertFailsWith<ApiError> { client(p, unauthenticated = { called++ }).get("/me") }
        assertEquals(1, called)
        assertEquals("unauthenticated", e.code)
        assertFalse(secret in (e.message) || secret in e.toString())
    }

    @Test
    fun tokenExchangeIsSentWithoutAuthorization() = runTest {
        val p = fake()
        p.reply(201, """{"data":{"token":"t"}}""")
        client(p).send("POST", "/auth/token/exchange", body = buildJsonObject { put("code", "ABCDE12345") }, authenticated = false)
        assertNull(p.requests.single().headers["Authorization"])
    }

    @Test
    fun rateLimitWaitsRetryAfterThenRetries() = runTest {
        val p = fake()
        p.reply(429, """{"code":"rate_limited"}""", mapOf("Retry-After" to "7"))
        p.reply(200, """{"data":1}""")
        val waits = ArrayList<Long>()
        val seen = ArrayList<Int>()
        client(p, waits = waits, onWait = { seen += it }).get("/x")
        assertEquals(listOf(7000L), waits)
        assertEquals(listOf(7), seen)
        assertEquals(2, p.requests.size)
    }

    @Test
    fun rateLimitWithTooLongWaitIsReportedNotSlept() = runTest {
        val p = fake()
        p.reply(429, """{"code":"rate_limited"}""", mapOf("Retry-After" to "600"))
        val waits = ArrayList<Long>()
        val e = assertFailsWith<ApiError> { client(p, waits = waits).get("/x") }
        assertEquals(600, e.retryAfterSeconds)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun rateLimitGivesUpAfterThreeWaits() = runTest {
        val p = fake()
        repeat(4) { p.reply(429, "", mapOf("Retry-After" to "1")) }
        assertFailsWith<ApiError> { client(p).get("/x") }
        assertEquals(4, p.requests.size)
    }

    @Test
    fun sameServerRedirectIsFollowedWithTokenAndFinalUrlKept() = runTest {
        val p = fake()
        p.reply(301, "", mapOf("Location" to "/api/v1/sites/kept"))
        p.reply(200, """{"data":{"uuid":"kept"}}""")
        val r = client(p).get("/sites/old")
        assertEquals("https://subatlas.test/api/v1/sites/kept", r.url)
        assertEquals("Bearer $secret", p.requests[1].headers["Authorization"])
    }

    @Test
    fun crossOriginRedirectIsRefusedAndTokenNeverSent() = runTest {
        val p = fake()
        p.reply(302, "", mapOf("Location" to "https://evil.test/steal"))
        assertFailsWith<ApiError> { client(p).get("/x") }
        assertEquals(1, p.requests.size)
    }

    @Test
    fun redirectToThePublicAddressOfTheSiteIsReadOnOurServer() = runTest {
        val p = fake()
        p.reply(301, "", mapOf("Location" to "http://localhost:8080/api/v1/sites/kept"))
        p.reply(200, """{"data":{"uuid":"kept"}}""")
        val r = client(p, server = "http://10.0.2.2:8080").get("/sites/old")
        assertEquals("http://10.0.2.2:8080/api/v1/sites/kept", r.url)
    }

    @Test
    fun photoAddressOfThePublicSiteIsReadOnOurServerWithToken() = runTest {
        val p = fake()
        p.reply(200, "img")
        client(p, server = "http://10.0.2.2:8080").getBytes("http://localhost:8080/api/v1/photos/u/photo")
        assertEquals("http://10.0.2.2:8080/api/v1/photos/u/photo", p.requests.single().url)
        assertEquals("Bearer $secret", p.requests.single().headers["Authorization"])
    }

    @Test
    fun cleartextRefusedOutsideDebug() = runTest {
        val p = fake(cleartext = false)
        assertFailsWith<InsecureServerException> { client(p, server = "http://10.0.2.2:8080").get("/me") }
        assertTrue(p.requests.isEmpty())
        // HTTPS reste accepté.
        p.reply(200, """{"data":1}""")
        client(p).get("/me")
    }

    @Test
    fun cleartextAcceptedInDebug() = runTest {
        val p = fake(cleartext = true)
        p.reply(200, """{"data":1}""")
        client(p, server = "http://10.0.2.2:8080").get("/me")
        assertEquals("http://10.0.2.2:8080/api/v1/me", p.requests.single().url)
    }

    @Test
    fun networkFailureIsNotAnApiError() = runTest {
        val p = fake()
        p.fail()
        assertFailsWith<NetworkException> { client(p).get("/x") }
    }

    @Test
    fun photoDownloadSendsTokenOnlyToTheServer() = runTest {
        val p = fake()
        p.reply(200, "img")
        p.reply(200, "img")
        val c = client(p)
        c.getBytes("https://subatlas.test/media/a.jpg")
        c.getBytes("https://cdn.other.test/a.jpg")
        assertEquals("Bearer $secret", p.requests[0].headers["Authorization"])
        assertNull(p.requests[1].headers["Authorization"])
        assertNull(p.requests[1].headers["X-Device-Id"])
    }

    @Test
    fun multipartBodyIsWellFormed() {
        val body = Multipart.build(
            listOf(MultipartPart("photo", byteArrayOf(1, 2, 3), "a\"b.jpg", "image/jpeg"), MultipartPart("caption", "hé".encodeToByteArray())),
            boundary = "BB",
        )
        assertEquals("multipart/form-data; boundary=BB", body.contentType)
        val text = body.bytes.decodeToString()
        assertTrue(text.startsWith("--BB\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"a'b.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        assertTrue(text.contains("\r\n--BB\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\nhé\r\n--BB--\r\n"))
        val headerLen = "--BB\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"a'b.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".length
        assertEquals(listOf<Byte>(1, 2, 3), body.bytes.slice(headerLen until headerLen + 3))
    }

    @Test
    fun multipartIsSentThroughClient() = runTest {
        val p = fake()
        p.reply(201, """{"data":[{"uuid":"ph1"}]}""")
        val body = Multipart.build(listOf(MultipartPart("photo", byteArrayOf(9), "p.jpg", "image/jpeg")))
        client(p).postMultipart("/dives/u1/photos", body)
        assertTrue(p.requests.single().contentType!!.startsWith("multipart/form-data; boundary="))
    }
}
