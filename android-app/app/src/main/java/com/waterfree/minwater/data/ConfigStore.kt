package com.waterfree.minwater.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.util.UUID

object ConfigStore {
    private const val LEGACY_PREFS_NAME = "min_water_app"
    private const val SECURE_PREFS_NAME = "min_water_app_secure"
    private const val KEY_CONFIG = "config_json"
    private val gson = Gson()

    fun load(context: Context): AppConfig {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_CONFIG, null)
        val config = if (raw.isNullOrBlank()) {
            AppConfig()
        } else {
            runCatching { gson.fromJson(raw, AppConfig::class.java) }.getOrDefault(AppConfig())
        }
        if (config.deviceUuid.isBlank()) {
            config.deviceUuid = UUID.randomUUID().toString()
        }
        return config
    }

    fun save(context: Context, config: AppConfig) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val securePrefs = createSecurePrefs(appContext)
        if (securePrefs != null) {
            migrateLegacyPrefsIfNeeded(appContext, securePrefs)
            return securePrefs
        }
        return appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun createSecurePrefs(context: Context): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context, securePrefs: SharedPreferences) {
        if (securePrefs.contains(KEY_CONFIG)) return
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyConfig = legacyPrefs.getString(KEY_CONFIG, null) ?: return
        securePrefs.edit().putString(KEY_CONFIG, legacyConfig).apply()
        legacyPrefs.edit().remove(KEY_CONFIG).apply()
    }
}
