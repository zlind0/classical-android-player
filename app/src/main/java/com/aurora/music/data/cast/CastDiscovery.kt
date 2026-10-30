package com.aurora.music.data.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.google.gson.Gson
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 本机在投屏网络里的固定身份：随机 UUID + 机型名，只存在本机。 */
class CastIdentity(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("aurora_cast_identity", Context.MODE_PRIVATE)

    val id: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    val name: String = prefs.getString("name", null)
        ?: run {
            val model = (Build.MODEL ?: "Android")
                .replace(Regex("[^A-Za-z0-9 _\\-]"), " ")
                .trim()
                .ifBlank { "Android" }
            "$model · ${id.take(4).uppercase()}".also {
                prefs.edit().putString("name", it).apply()
            }
        }
}

/**
 * 局域网发现：mDNS/NSD（主）+ UDP 广播探测（备用）。
 *
 * - 注册 `_aurora-cast._tcp.`，TXT 里带 id/name/port/state；
 * - UDP 47823 收 PROBE 回 OFFER，探测时向每个网卡的广播地址各发一轮；
 * - 两条通道按设备 id 去重，任何一条通就能出现在投屏列表里。
 *
 * 浏览只在投屏选择页打开时启用（省电）；服务注册常驻，保证别的设备随时能找到本机。
 */
class CastDiscovery(
    context: Context,
    val identity: CastIdentity,
    private val stateProvider: () -> String = { "idle" },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val nsd: NsdManager? =
        runCatching { appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    @Volatile private var serverPort: Int = 0
    @Volatile private var advertisedPort: Int = 0
    @Volatile private var registered: Boolean = false
    @Volatile private var registerInFlight: Boolean = false

    private val resolveLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val idByServiceName = HashMap<String, String>()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var browseJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var browseRefs = 0
    /** 资料库同步需要常驻发现：置位后投屏选择页关闭也不会停掉浏览。 */
    @Volatile private var persistentBrowse = false
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        scope.launch { udpLoop() }
        // 注册失败/换网后重试：mDNS 注册不像 UDP 那样能自愈
        scope.launch {
            while (isActive) {
                if (serverPort > 0 && !registered && !registerInFlight) registerNsd()
                delay(4_000)
            }
        }
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (advertisedPort > 0) {
                        registered = false
                        registerNsd()
                    }
                }

                override fun onLost(network: Network) {
                    registered = false
                }
            })
        }
    }

    /** 网页服务端口就绪后调用；端口变化会重新注册。 */
    fun advertise(port: Int) {
        if (port <= 0) return
        serverPort = port
        if (port != advertisedPort) {
            registered = false
            advertisedPort = port
            registerNsd()
            announce()
        }
    }

    /** App 启动时调用一次：局域网资料库要持续可见，浏览永不释放。 */
    fun acquirePersistentBrowse() {
        persistentBrowse = true
        acquireBrowse()
    }

    /** 打开投屏选择页时调用（引用计数，多个入口同时打开也不会互相打断）。 */
    fun acquireBrowse() {
        synchronized(resolveLock) {
            browseRefs += 1
            if (browseRefs > 1) return
        }
        acquireMulticastLock()
        browseJob = scope.launch {
            startDiscovery()
            probe()
            repeat(2) {
                delay(400)
                probe()
            }
            while (isActive) {
                // 常驻资料库同步时把探测降到 10 秒一次，省电；打开投屏选择页再恢复 2 秒。
                delay(if (persistentBrowse) 10_000L else 2_000L)
                // 有的机型 stop/start 太快会 onStartDiscoveryFailed；这里定期补一次
                if (discoveryListener == null) startDiscovery()
                probe()
                // 主动广播 offer：对端进程重启后 mDNS 有时不会重新回调 onServiceFound，
                // UDP offer 能让正在浏览的一端无需等待 probe 就重新发现本机。
                announce()
            }
        }
    }

    fun releaseBrowse() {
        if (persistentBrowse) return
        synchronized(resolveLock) {
            if (browseRefs > 0) browseRefs -= 1
            if (browseRefs > 0) return
        }
        browseJob?.cancel()
        browseJob = null
        stopDiscovery()
        synchronized(resolveLock) {
            resolveQueue.clear()
            idByServiceName.clear()
        }
        _devices.value = emptyList()
        releaseMulticastLock()
    }

    fun clear() {
        _devices.value = emptyList()
    }

    // ---- mDNS ----

    private fun registerNsd() {
        val n = nsd ?: return
        val port = serverPort
        if (port <= 0 || registerInFlight) return
        registerInFlight = true
        // 换网 / 端口变化时先注销旧注册，否则重新注册会拿到 ALREADY_ACTIVE 并永久失败
        registrationListener?.let { old -> runCatching { n.unregisterService(old) } }
        registrationListener = null
        val info = NsdServiceInfo().apply {
            serviceName = "Aurora-${identity.id.take(6)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            setAttribute("id", identity.id)
            setAttribute("name", identity.name)
            setAttribute("state", stateProvider())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered = true
                registerInFlight = false
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
                registerInFlight = false
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered = false
                registerInFlight = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registerInFlight = false
            }
        }
        registrationListener = listener
        runCatching { n.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                registered = false
                registerInFlight = false
            }
    }

    private fun startDiscovery() {
        val n = nsd ?: return
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.startsWith("Aurora-")) enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = synchronized(resolveLock) { idByServiceName.remove(serviceInfo.serviceName) }
                if (id != null) removeDevice(id)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryListener = null
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
        }
        discoveryListener = listener
        runCatching { n.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discoveryListener = null }
    }

    private fun stopDiscovery() {
        val n = nsd ?: return
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { n.stopServiceDiscovery(listener) }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        val start = synchronized(resolveLock) {
            // 同一个服务名只排一次
            if (resolveQueue.any { it.serviceName == info.serviceName }) return
            resolveQueue.add(info)
            if (resolving) false else {
                resolving = true
                true
            }
        }
        if (start) resolveNext()
    }

    private fun resolveNext() {
        val info = synchronized(resolveLock) { resolveQueue.removeFirstOrNull() }
        if (info == null) {
            synchronized(resolveLock) { resolving = false }
            return
        }
        val n = nsd
        if (n == null) {
            synchronized(resolveLock) { resolving = false }
            return
        }
        runCatching {
            n.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    onResolved(serviceInfo)
                    resolveNext()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveNext()
                }
            })
        }.onFailure { resolveNext() }
    }

    private fun onResolved(info: NsdServiceInfo) {
        val txt = runCatching { info.attributes }.getOrNull().orEmpty()
            .mapValues { (_, v) -> runCatching { String(v, Charsets.UTF_8) }.getOrDefault("") }
        val id = txt["id"]?.takeIf { it.isNotBlank() } ?: return
        if (id == identity.id) return
        val host = info.host?.hostAddress?.substringBefore('%') ?: return
        // v1 只走 IPv4：HttpURLConnection 对 IPv6 literal 的 URL 拼装太容易出错
        if (host.contains(':')) return
        if (info.port <= 0) return
        val name = txt["name"]?.takeIf { it.isNotBlank() } ?: info.serviceName
        synchronized(resolveLock) { idByServiceName[info.serviceName] = id }
        upsert(
            CastDevice(
                id = id,
                name = name,
                host = host,
                port = info.port,
                source = "mdns",
                lastSeen = SystemClock.elapsedRealtime(),
            ),
        )
    }

    // ---- UDP ----

    private fun udpLoop() {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(UDP_PORT))
            }
        }.getOrNull() ?: return
        udpSocket = socket
        val buf = ByteArray(2048)
        while (scope.isActive && !socket.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            val ok = runCatching { socket.receive(pkt) }.isSuccess
            if (!ok) continue
            val text = runCatching { String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8) }.getOrNull() ?: continue
            val msg = runCatching { gson.fromJson(text, UdpMessage::class.java) }.getOrNull() ?: continue
            if (msg.v != 1 || msg.id.isBlank() || msg.id == identity.id) continue
            when (msg.t) {
                "probe" -> sendOffer(pkt.address, pkt.port)
                "offer" -> {
                    if (msg.port <= 0) continue
                    upsert(
                        CastDevice(
                            id = msg.id,
                            name = msg.name.ifBlank { "Aurora" },
                            host = pkt.address.hostAddress?.substringBefore('%') ?: continue,
                            port = msg.port,
                            source = "udp",
                            lastSeen = SystemClock.elapsedRealtime(),
                        ),
                    )
                }
            }
        }
    }

    private fun probe() {
        val socket = udpSocket ?: return
        val bytes = gson.toJson(
            UdpMessage(t = "probe", id = identity.id, name = identity.name, port = serverPort),
        ).toByteArray(Charsets.UTF_8)
        broadcastAddresses().forEach { addr ->
            runCatching {
                socket.send(DatagramPacket(bytes, bytes.size, addr, UDP_PORT))
            }
        }
    }

    /** 主动向局域网广播“我在线”，不依赖对端 probe 或 mDNS 重新发现。 */
    private fun announce() {
        val socket = udpSocket ?: return
        val msg = UdpMessage(
            t = "offer",
            id = identity.id,
            name = identity.name,
            port = serverPort,
            state = stateProvider(),
        )
        val bytes = gson.toJson(msg).toByteArray(Charsets.UTF_8)
        broadcastAddresses().forEach { addr ->
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, addr, UDP_PORT)) }
        }
    }

    private fun sendOffer(addr: InetAddress, port: Int) {
        val socket = udpSocket ?: return
        val msg = UdpMessage(
            t = "offer",
            id = identity.id,
            name = identity.name,
            port = serverPort,
            state = stateProvider(),
        )
        val bytes = gson.toJson(msg).toByteArray(Charsets.UTF_8)
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, addr, port)) }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching { out += InetAddress.getByName("255.255.255.255") }
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                if (!up) return@forEach
                val name = iface.name.orEmpty()
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ppp")) return@forEach
                iface.interfaceAddresses.forEach { ia ->
                    val b = ia.broadcast
                    if (b is Inet4Address) out += b
                }
            }
        }
        return out
    }

    // ---- 列表 ----

    private fun upsert(d: CastDevice) {
        _devices.update { list ->
            val i = list.indexOfFirst { it.id == d.id }
            if (i < 0) {
                list + d
            } else {
                list.toMutableList().also { it[i] = d }
            }
        }
    }

    private fun removeDevice(id: String) {
        _devices.update { list -> list.filterNot { it.id == id } }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = runCatching {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return
        multicastLock = runCatching {
            wifi.createMulticastLock("aurora-cast").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private data class UdpMessage(
        val t: String = "",
        val v: Int = 1,
        val id: String = "",
        val name: String = "",
        val port: Int = 0,
        val state: String = "",
    )

    private companion object {
        const val SERVICE_TYPE = "_aurora-cast._tcp."
        const val UDP_PORT = 47823
    }
}
