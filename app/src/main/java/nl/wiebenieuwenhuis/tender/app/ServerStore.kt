package nl.wiebenieuwenhuis.tender.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import nl.wiebenieuwenhuis.tender.TenderApplication
import nl.wiebenieuwenhuis.tender.api.FrigateJson
import nl.wiebenieuwenhuis.tender.api.ServerAddress
import java.util.UUID

/** A saved Frigate server. The password lives in encrypted storage. */
@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val remoteUrl: String? = null,
    val username: String? = null,
    val tintIndex: Int = 0,
) {
    val initial: String get() = (name.trim().firstOrNull() ?: 'F').uppercase()

    var password: String?
        get() = Secrets.read(id)
        set(value) { if (value.isNullOrEmpty()) Secrets.delete(id) else Secrets.write(id, value) }

    companion object {
        /** Accepts "host:port" or a full URL; the best guess. */
        fun normalizedUrl(raw: String): String? = ServerAddress.candidates(raw).firstOrNull()?.toString()?.trimEnd('/')
    }
}

/** Passwords, encrypted with a key in the Android Keystore. */
object Secrets {
    private val prefs: SharedPreferences by lazy {
        val ctx = TenderApplication.context
        runCatching {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(ctx, "secrets", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrElse { ctx.getSharedPreferences("secrets_fallback", Context.MODE_PRIVATE) }
    }
    fun read(account: String): String? = prefs.getString(account, null)
    fun write(account: String, value: String) { prefs.edit().putString(account, value).apply() }
    fun delete(account: String) { prefs.edit().remove(account).apply() }
}

/** The app's own settings, as UserDefaults in the iOS app. */
object Prefs {
    val prefs: SharedPreferences by lazy { TenderApplication.context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    fun loadServers(): List<ServerConfig> = prefs.getString("servers", null)?.let {
        runCatching { FrigateJson.decodeFromString(ListSerializer(ServerConfig.serializer()), it) }.getOrNull()
    } ?: emptyList()

    fun saveServers(list: List<ServerConfig>) {
        prefs.edit().putString("servers", FrigateJson.encodeToString(ListSerializer(ServerConfig.serializer()), list)).apply()
    }

    fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun string(key: String): String? = prefs.getString(key, null)
    fun setString(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
    fun setLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
}
