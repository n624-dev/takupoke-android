package jp.n624.takupoke.android

import android.net.Uri
import jp.n624.takupoke.core.*
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.RequestBody
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assert.fail
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class AuthTest {
    @Test fun revisionRaceKeepsOldLinksAndOnlyRequestedDataIsDownloaded() = kotlinx.coroutines.runBlocking {
        val base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(base.cacheDir, "auth-parity-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        val context = object : android.content.ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val db = Database(context); val tokens = Tokens(); val revision = "a".repeat(43)
        val old = LinksPayload("v1", "sha256-" + "b".repeat(64), listOf(LinkCategory("fixture", "架空カテゴリ", 0, emptyList())))
        val next = old.copy(linksVersion = "sha256-" + "c".repeat(64))
        var damaged = true; var downloads = 0
        val transport = object : Transport {
            override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult = when (url) {
                Endpoints.API + "/links-revision" -> HttpResult(200, byteArrayOf(), mapOf("etag" to "\"$revision\""))
                Endpoints.API + "/mapping-revision", Endpoints.API + "/timetable-times-revision" -> HttpResult(304, byteArrayOf(), emptyMap())
                Endpoints.API + "/links" -> {
                    downloads++; assertEquals("Bearer synthetic-access", headers["Authorization"])
                    HttpResult(200, json.encodeToString(LinksPayload.serializer(), next).toByteArray(), mapOf("content-type" to "application/json", "etag" to "\"fixture\"", "x-links-revision" to if (damaged) "c".repeat(43) else revision))
                }
                Endpoints.ISSUER + "/oauth/token", Endpoints.ISSUER + "/oauth/jwks" -> tokens.request(url, headers, body, maxBytes, head)
                else -> error("Unexpected request rejected")
            }
        }
        val repository = AppRepository(context, transport, db, MemorySettings())
        try {
            repository.activate(false)
            db.put("links", json.encodeToString(LinksPayload.serializer(), old))
            db.put("mapping", json.encodeToString(Mapping.serializer(), Mapping(emptyList(), emptyList(), emptyList())))
            db.put("times", json.encodeToString(TimesPayload.serializer(), TimesPayload(1, emptyList())))
            listOf("links-revision", "mapping-revision", "timetable-times-revision").forEach { db.put("revision:$it", "b".repeat(43)) }
            suspend fun authenticate() {
                assertTrue(repository.prepareAuth())
                val request = repository.auth.begin()
                tokens.nonce = request.getQueryParameter("nonce")!!; tokens.challenge = request.getQueryParameter("code_challenge")!!
                repository.finishAuth(Uri.parse(Endpoints.CALLBACK).buildUpon().appendQueryParameter("state", request.getQueryParameter("state")).appendQueryParameter("code", "synthetic-code").build())
            }
            authenticate(); assertEquals(old, repository.state.value.links)
            assertTrue("links-revision" in repository.state.value.accountErrors)
            assertEquals("b".repeat(43), db.value("revision:links-revision"))
            damaged = false; authenticate(); assertEquals(next, repository.state.value.links)
            assertFalse("links-revision" in repository.state.value.accountErrors)
            assertEquals(revision, db.value("revision:links-revision")); assertEquals(2, downloads)
            assertNotNull(repository.state.value.mapping); assertNotNull(repository.state.value.times)
        } finally { repository.stopObserving(); db.close(); directory.deleteRecursively() }
    }
    @Test fun androidCanResolveTheRegisteredHostlessCallback() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(Endpoints.CALLBACK + "?state=synthetic&code=synthetic")).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        assertTrue(context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.packageName == context.packageName && it.activityInfo.name == MainActivity::class.java.name })
    }
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun encode(text: String) = encode(text.toByteArray())
    @Test fun pkceSignedIdTokenAndOneUseCallback() {
        val fake = Tokens(); val auth = Oidc(fake); val request = auth.begin(); fake.nonce = request.getQueryParameter("nonce")!!; fake.challenge = request.getQueryParameter("code_challenge")!!
        val callback = Uri.parse(Endpoints.CALLBACK).buildUpon().appendQueryParameter("state", request.getQueryParameter("state")).appendQueryParameter("code", "synthetic-code").build()
        assertEquals("synthetic-access", auth.finish(callback)); assertEquals(2, fake.requests)
        try { auth.finish(callback); fail("Replayed callback accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun wrongStateAndDuplicateParametersCannotExchangeCode() {
        val fake = Tokens(); val auth = Oidc(fake); val request = auth.begin()
        val callback = Uri.parse(Endpoints.CALLBACK).buildUpon().appendQueryParameter("state", "wrong").appendQueryParameter("code", "synthetic-code").build()
        try { auth.finish(callback); fail("Wrong state accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(0, fake.requests)
        val valid = auth.begin(); val duplicate = Uri.parse(Endpoints.CALLBACK).buildUpon().appendQueryParameter("state", valid.getQueryParameter("state")).appendQueryParameter("state", valid.getQueryParameter("state")).appendQueryParameter("code", "synthetic-code").build()
        try { auth.finish(duplicate); fail("Duplicate state accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(0, fake.requests)
    }
    @Test fun invalidIssuerNonceExpiryAndSignatureAreRejected() {
        listOf("issuer", "nonce", "expiry", "signature", "algorithm", "scope").forEach { damage ->
            val fake = Tokens(damage); val auth = Oidc(fake); val request = auth.begin(); fake.nonce = request.getQueryParameter("nonce")!!; fake.challenge = request.getQueryParameter("code_challenge")!!
            val callback = Uri.parse(Endpoints.CALLBACK).buildUpon().appendQueryParameter("state", request.getQueryParameter("state")).appendQueryParameter("code", "synthetic-code").build()
            try { auth.finish(callback); fail("Invalid $damage accepted") } catch (_: IllegalArgumentException) { } catch (_: SignatureException) { }
        }
    }
    private inner class Tokens(private val damage: String = "") : Transport {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var nonce = ""; var challenge = ""; var requests = 0
        override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult {
            requests++
            val text = when (url) {
                Endpoints.ISSUER + "/oauth/token" -> {
                    val form = body as FormBody; val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals(challenge, encode(MessageDigest.getInstance("SHA-256").digest(values.getValue("code_verifier").toByteArray())))
                    assertEquals(Endpoints.CLIENT, values["client_id"]); assertEquals(Endpoints.CALLBACK, values["redirect_uri"])
                    val now = System.currentTimeMillis() / 1000
                    val header = encode("""{"alg":"${if (damage == "algorithm") "none" else "ES256"}","typ":"JWT","kid":"synthetic"}""")
                    val claims = encode(buildJsonObject { put("iss", if (damage == "issuer") "https://wrong.invalid" else Endpoints.ISSUER); put("aud", Endpoints.CLIENT); put("nonce", if (damage == "nonce") "wrong" else nonce); put("sub", "synthetic-subject"); put("acr", "urn:takunin:assurance:strict"); putJsonArray("amr") { add("microsoft") }; put("iat", now); put("exp", if (damage == "expiry") now - 1 else now + 600) }.toString())
                    val signing = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update("$header.$claims".toByteArray()) }.sign()
                    var index = 2
                    fun integer(): ByteArray { check(signing[index++].toInt() == 2); val length = signing[index++].toInt() and 255; val v = signing.copyOfRange(index, index + length); index += length; val stripped = v.dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - stripped.size) + stripped }
                    val raw = integer() + integer(); if (damage == "signature") raw[0] = (raw[0].toInt() xor 1).toByte()
                    """{"token_type":"Bearer","expires_in":600,"scope":"${if (damage == "scope") "openid" else "openid mapping.read links.read"}","id_token":"$header.$claims.${encode(raw)}","access_token":"synthetic-access"}"""
                }
                Endpoints.ISSUER + "/oauth/jwks" -> { val public = key.public as ECPublicKey
                    fun coordinate(n: java.math.BigInteger): String { val v = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray(); return encode(ByteArray(32 - v.size) + v) }
                    """{"keys":[{"kid":"synthetic","kty":"EC","crv":"P-256","x":"${coordinate(public.w.affineX)}","y":"${coordinate(public.w.affineY)}"}]}"""
                }
                else -> error("Unexpected URL rejected; no real network allowed")
            }
            return HttpResult(200, text.toByteArray(), emptyMap())
        }
    }
}
