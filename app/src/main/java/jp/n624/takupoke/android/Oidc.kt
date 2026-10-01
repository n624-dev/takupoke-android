package jp.n624.takupoke.android

import android.net.Uri
import jp.n624.takupoke.core.json
import kotlinx.serialization.json.*
import okhttp3.FormBody
import java.math.BigInteger
import java.security.*
import java.security.spec.*
import java.util.Base64

class Oidc(private val transport: Transport) {
    private data class Session(val verifier: String, val state: String, val nonce: String, val created: Long)
    private var session: Session? = null
    private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun begin(): Uri {
        val s = Session(random(), random(), random(), System.currentTimeMillis()); session = s
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.verifier.toByteArray()))
        return Uri.parse(Endpoints.ISSUER + "/oauth/authorize").buildUpon().appendQueryParameter("response_type", "code").appendQueryParameter("client_id", Endpoints.CLIENT).appendQueryParameter("redirect_uri", Endpoints.CALLBACK).appendQueryParameter("scope", "openid mapping.read links.read").appendQueryParameter("state", s.state).appendQueryParameter("nonce", s.nonce).appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256").build()
    }
    fun cancel() { session = null }
    fun finish(uri: Uri): String {
        val s = requireNotNull(session); session = null
        require(System.currentTimeMillis() - s.created < 600000 && uri.scheme == "jp.n624.takupoke.android" && uri.authority == null && uri.path == "/oauth/callback" && uri.fragment == null)
        require(uri.getQueryParameters("state") == listOf(s.state) && uri.getQueryParameters("code").size == 1 && uri.getQueryParameters("error").isEmpty())
        val code = requireNotNull(uri.getQueryParameter("code")); require(code.isNotBlank())
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", Endpoints.CLIENT).add("redirect_uri", Endpoints.CALLBACK).add("code_verifier", s.verifier).build()
        val response = transport.request(Endpoints.ISSUER + "/oauth/token", body = form, maxBytes = 65536)
        require(response.status == 200)
        val token = json.parseToJsonElement(response.bytes.decodeToString()).jsonObject
        require(token.getValue("token_type").jsonPrimitive.isString && token.getValue("token_type").jsonPrimitive.content == "Bearer" && !token.getValue("expires_in").jsonPrimitive.isString && token.getValue("expires_in").jsonPrimitive.long in 1..600)
        require(token.getValue("scope").jsonPrimitive.isString && token.getValue("scope").jsonPrimitive.content.split(' ').toSet() == setOf("openid", "mapping.read", "links.read"))
        verify(token.getValue("id_token").jsonPrimitive.content, s.nonce)
        return token.getValue("access_token").jsonPrimitive.let { require(it.isString && it.content.length in 1..32768); it.content }
    }
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun verify(jwt: String, nonce: String) {
        require(jwt.length <= 32768)
        val parts = jwt.split('.'); require(parts.size == 3)
        val header = json.parseToJsonElement(decode(parts[0]).decodeToString()).jsonObject
        val claims = json.parseToJsonElement(decode(parts[1]).decodeToString()).jsonObject
        fun claim(name: String) = claims.getValue(name).jsonPrimitive.let { require(it.isString); it.content }
        require(header.getValue("alg").jsonPrimitive.content == "ES256" && header.getValue("typ").jsonPrimitive.content == "JWT")
        val kid = header.getValue("kid").jsonPrimitive.content; require(kid.isNotBlank())
        require(claim("iss") == Endpoints.ISSUER && claim("aud") == Endpoints.CLIENT && claim("nonce") == nonce && claim("acr") == "urn:takunin:assurance:strict" && claim("sub").isNotBlank())
        require(claims.getValue("amr").jsonArray.map { it.jsonPrimitive.content } == listOf("microsoft"))
        require(!claims.getValue("exp").jsonPrimitive.isString && !claims.getValue("iat").jsonPrimitive.isString)
        val exp = claims.getValue("exp").jsonPrimitive.long; val iat = claims.getValue("iat").jsonPrimitive.long; val now = System.currentTimeMillis() / 1000
        require(exp > now && iat <= now + 30 && exp > iat && exp - iat <= 600)
        val response = transport.request(Endpoints.ISSUER + "/oauth/jwks", maxBytes = 65536); require(response.status == 200)
        val jwk = json.parseToJsonElement(response.bytes.decodeToString()).jsonObject.getValue("keys").jsonArray.map { it.jsonObject }.single { it["kid"]?.jsonPrimitive?.content == kid }
        require(jwk.getValue("kty").jsonPrimitive.content == "EC" && jwk.getValue("crv").jsonPrimitive.content == "P-256")
        val parameters = AlgorithmParameters.getInstance("EC"); parameters.init(ECGenParameterSpec("secp256r1"))
        val point = ECPoint(BigInteger(1, decode(jwk.getValue("x").jsonPrimitive.content)), BigInteger(1, decode(jwk.getValue("y").jsonPrimitive.content)))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec::class.java)))
        val raw = decode(parts[2]); require(raw.size == 64)
        fun integer(bytes: ByteArray): ByteArray { val stripped = bytes.dropWhile { it == 0.toByte() }.toByteArray(); val v = if (stripped.isEmpty()) byteArrayOf(0) else stripped; val positive = if (v[0] < 0) byteArrayOf(0) + v else v; return byteArrayOf(2, positive.size.toByte()) + positive }
        val ints = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64)); val der = byteArrayOf(0x30, ints.size.toByte()) + ints
        val signature = Signature.getInstance("SHA256withECDSA"); signature.initVerify(publicKey); signature.update("${parts[0]}.${parts[1]}".toByteArray()); require(signature.verify(der))
    }
}
