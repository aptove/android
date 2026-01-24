package com.acp.chat.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.acp.chat.data.model.ConnectionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun saveCredentials(agentId: String, config: ConnectionConfig) {
        val configJson = json.encodeToString(config)
        sharedPreferences.edit()
            .putString(agentId, configJson)
            .apply()
    }

    fun getCredentials(agentId: String): ConnectionConfig? {
        val configJson = sharedPreferences.getString(agentId, null) ?: return null
        return try {
            json.decodeFromString<ConnectionConfig>(configJson)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteCredentials(agentId: String) {
        sharedPreferences.edit()
            .remove(agentId)
            .apply()
    }

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
}
