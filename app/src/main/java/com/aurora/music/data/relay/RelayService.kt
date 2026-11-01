package com.aurora.music.data.relay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 公网中继的进程内服务：配置、连接、本机代理、快照身份。
 * 通过 [endpoint] 把自己伪装成一台「局域网设备」，同步逻辑无需区分传输方式。
 */
class RelayService(context: Context) {

    data class Endpoint(
        val deviceId: String,
        val deviceName: String,
        val host: String,
        val port: Int,
    )

    data class State(
        val enabled: Boolean = false,
        val configured: Boolean = false,
        val connected: Boolean = false,
        val hostOnline: Boolean = false,
        val deviceId: String = "",
        val deviceName: String = "",
        val proxyPort: Int = 0,
        val libVersion: Long = 0,
        val storeUsed: Long = 0,
        val storeCap: Long = 0,
        val keyId: Long = 0,
        val lastError: String? = null,
    )

    sealed class PairState {
        data object Idle : PairState()
        data object Joining : PairState()
        data class Failed(val message: String) : PairState()
        data class Joined(val deviceName: String) : PairState()
    }

    private val appContext = context.applicationContext
    val config = RelayConfig(appContext)
    private val client = RelayClient(config)
    private val store = RelayStore(appContext, config)
    private val proxy = RelayProxy(config, client, store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _pairState = MutableStateFlow<PairState>(PairState.Idle)
    val pairState: StateFlow<PairState> = _pairState.asStateFlow()

    private var identityJob: Job? = null

    init {
        // 冷启动时先用磁盘上的快照身份，避免重新同步前看不到中继设备。
        val cached = store.cachedVersion()
        if (cached != null) {
            _state.value = _state.value.copy(
                deviceId = cached.deviceId,
                deviceName = cached.deviceName,
                libVersion = cached.version,
                enabled = config.current.enabled,
                configured = config.current.isUsable(),
                keyId = config.current.keyId,
            )
        }
    }

    fun start() {
        val settings = config.current
        if (!settings.isUsable()) {
            _state.value = State(
                enabled = settings.enabled,
                configured = settings.isUsable(),
                keyId = settings.keyId,
            )
            return
        }
        client.start(scope)
        proxy.start(scope, settings.proxyPort)
        _state.value = _state.value.copy(
            enabled = true,
            configured = true,
            proxyPort = proxy.port,
            keyId = settings.keyId,
            lastError = null,
        )
        identityJob?.cancel()
        identityJob = scope.launch {
            while (isActive) {
                refreshIdentity()
                delay(15_000)
            }
        }
    }

    private suspend fun refreshIdentity() {
        val status = client.status.value
        var error: String? = null
        if (!status.connected) {
            _state.value = _state.value.copy(
                connected = false,
                hostOnline = false,
                lastError = status.lastError,
            )
            return
        }
        val snapshot = runCatching { store.ensureSnapshot(client) }
            .onFailure { error = it.message }
            .getOrNull()
        val latest = client.status.value
        _state.value = _state.value.copy(
            connected = latest.connected,
            hostOnline = latest.hostOnline,
            libVersion = snapshot?.version ?: latest.libVersion,
            deviceId = snapshot?.deviceId ?: store.deviceId().orEmpty(),
            deviceName = snapshot?.deviceName ?: store.deviceName().orEmpty(),
            proxyPort = proxy.port,
            storeUsed = latest.storeUsed,
            storeCap = latest.storeCap,
            keyId = config.current.keyId,
            lastError = error ?: latest.lastError,
        )
    }

    fun stop() {
        identityJob?.cancel()
        identityJob = null
        client.stop()
        proxy.stop()
        _state.value = _state.value.copy(connected = false, hostOnline = false, proxyPort = 0)
    }

    fun restart() {
        stop()
        start()
    }

    /**
     * 从 `podrelay://pair?...` 配对码加入：请求 NAS 的局域网接口，取回证书与密钥。
     * 返回 false 表示不是配对码。
     */
    fun acceptPairUri(raw: String?): Boolean {
        if (RelayRemotePairing.looksLike(raw)) {
            scope.launch {
                _pairState.value = PairState.Joining
                runCatching { RelayRemotePairing.decode(raw) }
                    .onSuccess { credentials ->
                        applyCredentials(
                            addr = credentials.relayAddr,
                            fingerprint = credentials.relayFingerprint,
                            certPem = credentials.certPem,
                            keyPem = credentials.keyPem,
                            mediaKey = credentials.mediaKeyB64,
                        )
                        _pairState.value = PairState.Joined(
                            credentials.deviceName.ifBlank { "公网中继" },
                        )
                    }
                    .onFailure { error ->
                        _pairState.value = PairState.Failed(error.message ?: "配对失败")
                    }
            }
            return true
        }
        val uri = RelayPairUri.parse(raw) ?: return false
        scope.launch {
            _pairState.value = PairState.Joining
            val deviceName = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
            runCatching { RelayPairing.fetch(uri, deviceName) }
                .onSuccess { credentials ->
                    applyCredentials(
                        addr = credentials.relayAddr,
                        fingerprint = credentials.relayFingerprint,
                        certPem = credentials.certPem,
                        keyPem = credentials.keyPem,
                        mediaKey = credentials.mediaKeyB64,
                    )
                    _pairState.value = PairState.Joined(credentials.deviceName)
                }
                .onFailure { error ->
                    _pairState.value = PairState.Failed(error.message ?: "配对失败")
                }
        }
        return true
    }

    private fun applyCredentials(
        addr: String,
        fingerprint: String,
        certPem: String,
        keyPem: String,
        mediaKey: String,
    ) {
        val current = config.current
        config.save(
            current.copy(
                enabled = true,
                addr = addr,
                fingerprint = fingerprint,
                certPem = certPem,
                keyPem = keyPem,
                mediaKey = mediaKey,
            ),
        )
        store.clearSnapshot()
        restart()
    }

    fun clearPairState() {
        _pairState.value = PairState.Idle
    }

    fun saveSettings(settings: RelaySettings) {
        config.save(settings)
        store.clearSnapshot()
        restart()
    }

    /** 供 RemoteLibraryManager 使用的设备端点。 */
    fun endpoint(): Endpoint? {
        val state = _state.value
        if (!state.enabled || state.deviceId.isBlank() || state.proxyPort <= 0) return null
        return Endpoint(
            deviceId = state.deviceId,
            deviceName = state.deviceName.ifBlank { "公网中继" },
            host = "127.0.0.1",
            port = state.proxyPort,
        )
    }

    fun currentState(): State = _state.value

    fun keyId(): Long = config.current.keyId
}
