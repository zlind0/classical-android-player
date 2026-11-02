package com.aurora.music.data.relay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用户在「设置 → 集成 → 公网中继」里填的内容。 */
data class RelaySettings(
    val enabled: Boolean = false,
    val addr: String = "",
    val fingerprint: String = "",
    val certPem: String = "",
    val keyPem: String = "",
    val mediaKey: String = "",
    val proxyPort: Int = 47001,
) {
    val masterKey: ByteArray? get() = RelayCrypto.base64Key(mediaKey)
    val keyId: Long get() = masterKey?.let { RelayCrypto.keyId(it) } ?: 0L

    fun isUsable(): Boolean =
        enabled &&
            addr.isNotBlank() &&
            fingerprint.isNotBlank() &&
            certPem.isNotBlank() &&
            keyPem.isNotBlank() &&
            masterKey != null
}

class RelayConfig(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("aurora_relay", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RelaySettings> = _settings.asStateFlow()

    val current: RelaySettings get() = _settings.value

    private fun load(): RelaySettings = RelaySettings(
        enabled = prefs.getBoolean("enabled", false),
        addr = prefs.getString("addr", "").orEmpty(),
        fingerprint = prefs.getString("fingerprint", "").orEmpty(),
        certPem = prefs.getString("certPem", "").orEmpty(),
        keyPem = prefs.getString("keyPem", "").orEmpty(),
        mediaKey = prefs.getString("mediaKey", "").orEmpty(),
        proxyPort = prefs.getInt("proxyPort", 47001),
    )

    fun clear() {
        prefs.edit().clear().apply()
        _settings.value = RelaySettings()
    }

    fun save(settings: RelaySettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("addr", settings.addr.trim())
            .putString("fingerprint", settings.fingerprint.trim())
            .putString("certPem", settings.certPem)
            .putString("keyPem", settings.keyPem)
            .putString("mediaKey", settings.mediaKey.trim())
            .putInt("proxyPort", settings.proxyPort)
            .apply()
        _settings.value = settings.copy(addr = settings.addr.trim(), fingerprint = settings.fingerprint.trim())
    }
}
