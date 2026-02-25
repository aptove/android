package com.acp.chat.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.acp.chat.data.model.ConnectionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Credentials for a single transport endpoint, keyed by endpoint ID. */
@Serializable
data class TransportCredentials(
    val authToken: String? = null,
    val certFingerprint: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null
)

@Singleton
class CredentialStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "agent_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Agent credentials (ConnectionConfig, keyed by agentId)

    fun saveCredentials(agentId: String, config: ConnectionConfig) {
        sharedPreferences.edit()
            .putString(agentId, json.encodeToString(config))
            .apply()
    }

    fun getCredentials(agentId: String): ConnectionConfig? {
        val raw = sharedPreferences.getString(agentId, null) ?: return null
        return try { json.decodeFromString<ConnectionConfig>(raw) } catch (e: Exception) { null }
    }

    fun deleteCredentials(agentId: String) {
        sharedPreferences.edit().remove(agentId).apply()
    }

    // MARK: - Transport endpoint credentials (TransportCredentials, keyed by endpointId)

    fun saveTransportCredentials(endpointId: String, credentials: TransportCredentials) {
        sharedPreferences.edit()
            .putString("ep_$endpointId", json.encodeToString(credentials))
            .apply()
    }

    fun getTransportCredentials(endpointId: String): TransportCredentials? {
        val raw = sharedPreferences.getString("ep_$endpointId", null) ?: return null
        return try { json.decodeFromString<TransportCredentials>(raw) } catch (e: Exception) { null }
    }

    fun deleteTransportCredentials(endpointId: String) {
        sharedPreferences.edit().remove("ep_$endpointId").apply()
    }

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
}
