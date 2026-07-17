package app.fleetlight.mobile.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ControlCredentialStore {
    fun read(authority: String): ControlSession?
    fun write(session: ControlSession)
    fun remove(authority: String)
}

class AndroidControlCredentialStore(context: Context) : ControlCredentialStore {
    private val preferences = context.getSharedPreferences("control-credentials", Context.MODE_PRIVATE)

    override fun read(authority: String): ControlSession? {
        val key = preferenceKey(authority)
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
            cipher.updateAAD(authority.toByteArray(StandardCharsets.UTF_8))
            val plain = cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
                .toString(StandardCharsets.UTF_8)
            val split = plain.indexOf('\n')
            require(split >= 0)
            val second = plain.indexOf('\n', split + 1)
            require(second >= 0)
            val controlBase = plain.substring(0, split)
            val observerId = plain.substring(split + 1, second)
            require(observerId.isNotBlank())
            val token = plain.substring(second + 1)
            require(token.isNotBlank())
            ControlSession(authority = authority, controlBase = controlBase, token = token, observerId = observerId)
        }.getOrElse {
            preferences.edit { remove(key) }
            null
        }
    }

    override fun write(session: ControlSession) {
        require(session.token.isNotBlank() && '\n' !in session.token)
        require('\n' !in session.controlBase)
        val plain = "${session.controlBase}\n${session.observerId.orEmpty()}\n${session.token}".toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(session.authority.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        val packed = cipher.iv + encrypted
        preferences.edit {
            putString(preferenceKey(session.authority), Base64.encodeToString(packed, Base64.NO_WRAP))
        }
    }

    override fun remove(authority: String) {
        preferences.edit { remove(preferenceKey(authority)) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun preferenceKey(authority: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(StandardCharsets.UTF_8))
        return "session.${Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)}"
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fleetlight-control-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

class DeviceIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("control-device", Context.MODE_PRIVATE)

    val id: String
        get() = preferences.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { value ->
            preferences.edit { putString(KEY_ID, value) }
        }

    val name: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "Android device" }

    private companion object {
        const val KEY_ID = "installId"
    }
}

class ControlAuthorityStore(context: Context) {
    private val preferences = context.getSharedPreferences("control-authority", Context.MODE_PRIVATE)

    fun read(): String? = preferences.getString("feedEndpoint", null)?.let(EndpointPolicy::normalize)

    fun write(endpoint: String) {
        val normalized = requireNotNull(EndpointPolicy.normalize(endpoint))
        preferences.edit { putString("feedEndpoint", normalized) }
    }

    fun clear() {
        preferences.edit { clear() }
    }
}

class ControlJobStore(context: Context) {
    private val preferences = context.getSharedPreferences("control-active-job", Context.MODE_PRIVATE)

    fun read(): StoredControlJob? {
        val endpoint = preferences.getString("endpoint", null) ?: return null
        val controlBase = preferences.getString("controlBase", null) ?: return null
        val requestId = preferences.getString("requestId", null) ?: return null
        val jobId = preferences.getString("jobId", null)
        val action = ControlAction.fromWire(preferences.getString("action", null)) ?: return null
        val targets = preferences.getString("targetHostIds", null).orEmpty().lineSequence().filter(String::isNotBlank).toList()
        if (targets.isEmpty()) return null
        return StoredControlJob(endpoint, controlBase, jobId, requestId, action, targets)
    }

    fun write(job: StoredControlJob): Boolean {
        return preferences.edit()
            .putString("endpoint", job.endpoint)
            .putString("controlBase", job.controlBase)
            .putString("jobId", job.jobId)
            .putString("requestId", job.requestId)
            .putString("action", job.action.wireValue)
            .putString("targetHostIds", job.targetHostIds.joinToString("\n"))
            .commit()
    }

    fun clear(): Boolean = preferences.edit().clear().commit()
}

class ControlCheckStore(context: Context) {
    private val preferences = context.getSharedPreferences("control-active-check", Context.MODE_PRIVATE)

    fun read(): StoredControlCheck? {
        val rawEndpoint = preferences.getString("endpoint", null) ?: return null
        val stored = runCatching {
            val normalizedEndpoint = requireNotNull(EndpointPolicy.normalize(rawEndpoint))
            val controlBase = requireNotNull(preferences.getString("controlBase", null))
            require(ControlEndpointPolicy.baseForFeed(normalizedEndpoint) == controlBase)
            val requestId = requireNotNull(preferences.getString("requestId", null))
            require(requestId.canonicalUuidOrNull() != null)
            val checkId = preferences.getString("checkId", null)
            require(checkId == null || checkId.canonicalUuidOrNull() != null)
            StoredControlCheck(normalizedEndpoint, controlBase, checkId, requestId)
        }.getOrNull()
        if (stored == null) clear()
        return stored
    }

    fun write(check: StoredControlCheck): Boolean {
        require(EndpointPolicy.normalize(check.endpoint) == check.endpoint)
        require(ControlEndpointPolicy.baseForFeed(check.endpoint) == check.controlBase)
        require(check.requestId.canonicalUuidOrNull() != null)
        require(check.checkId == null || check.checkId.canonicalUuidOrNull() != null)
        return preferences.edit()
            .putString("endpoint", check.endpoint)
            .putString("controlBase", check.controlBase)
            .putString("checkId", check.checkId)
            .putString("requestId", check.requestId)
            .commit()
    }

    fun clear(): Boolean = preferences.edit().clear().commit()
}
