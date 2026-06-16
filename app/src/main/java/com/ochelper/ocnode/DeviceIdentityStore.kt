package com.ochelper.ocnode

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.json.JSONObject

/**
 * Ed25519 device identity used to authenticate this node with the OpenClaw gateway.
 * deviceId = sha256hex(rawPublicKey); the gateway challenges with a nonce that we sign.
 */
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val privateKeyPkcs8Base64: String,
    val createdAtMs: Long,
)

class DeviceIdentityStore(context: Context) {
    private val identityFile = File(context.filesDir, "openclaw/identity/device.json")
    @Volatile private var cached: DeviceIdentity? = null

    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        cached?.let { return it }
        val existing = load()
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = generate()
        save(fresh)
        cached = fresh
        return fresh
    }

    fun signPayload(payload: String, identity: DeviceIdentity): String? {
        return try {
            val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
            val pkInfo = PrivateKeyInfo.getInstance(privateKeyBytes)
            val parsed = pkInfo.parsePrivateKey()
            val rawPrivate = DEROctetString.getInstance(parsed).octets
            val privateKey = Ed25519PrivateKeyParameters(rawPrivate, 0)
            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            signer.update(payloadBytes, 0, payloadBytes.size)
            base64UrlEncode(signer.generateSignature())
        } catch (e: Throwable) {
            android.util.Log.e("DeviceAuth", "signPayload FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    fun publicKeyBase64Url(identity: DeviceIdentity): String? {
        return try {
            val raw = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
            base64UrlEncode(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun load(): DeviceIdentity? {
        return try {
            if (!identityFile.exists()) return null
            val obj = JSONObject(identityFile.readText(Charsets.UTF_8))
            val deviceId = obj.optString("deviceId")
            val pub = obj.optString("publicKeyRawBase64")
            val priv = obj.optString("privateKeyPkcs8Base64")
            if (deviceId.isBlank() || pub.isBlank() || priv.isBlank()) null
            else DeviceIdentity(deviceId, pub, priv, obj.optLong("createdAtMs"))
        } catch (_: Throwable) {
            null
        }
    }

    private fun save(identity: DeviceIdentity) {
        try {
            identityFile.parentFile?.mkdirs()
            val obj = JSONObject()
                .put("deviceId", identity.deviceId)
                .put("publicKeyRawBase64", identity.publicKeyRawBase64)
                .put("privateKeyPkcs8Base64", identity.privateKeyPkcs8Base64)
                .put("createdAtMs", identity.createdAtMs)
            identityFile.writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
            // best-effort only
        }
    }

    private fun generate(): DeviceIdentity {
        val kpGen = Ed25519KeyPairGenerator()
        kpGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpGen.generateKeyPair()
        val pubKey = kp.public as Ed25519PublicKeyParameters
        val privKey = kp.private as Ed25519PrivateKeyParameters
        val rawPublic = pubKey.encoded // 32 bytes
        val deviceId = sha256Hex(rawPublic)
        val pkcs8Bytes = PrivateKeyInfoFactory.createPrivateKeyInfo(privKey).encoded
        return DeviceIdentity(
            deviceId = deviceId,
            publicKeyRawBase64 = Base64.encodeToString(rawPublic, Base64.NO_WRAP),
            privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8Bytes, Base64.NO_WRAP),
            createdAtMs = System.currentTimeMillis(),
        )
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val out = CharArray(digest.size * 2)
        var i = 0
        for (byte in digest) {
            val v = byte.toInt() and 0xff
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
