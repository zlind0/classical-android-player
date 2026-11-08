package com.aurora.music.data.cast.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Advertises the AirPlay receiver over Bonjour.
 *
 * Both `_raop._tcp` (audio-only RAOP) and `_airplay._tcp` (screen mirroring)
 * are needed: iPhones look up `_airplay._tcp` for Control Center "Screen
 * Mirroring", Macs use `_raop._tcp` for sound output.
 */
class AirPlayMdns(
    context: Context,
    private val deviceName: String,
    private val deviceId: String,
    private val port: Int,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var raopListener: NsdManager.RegistrationListener? = null
    private var airplayListener: NsdManager.RegistrationListener? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        registerRaop()
        registerAirplay()
    }

    fun stop() {
        started = false
        raopListener?.let { runCatching { nsdManager.unregisterService(it) } }
        airplayListener?.let { runCatching { nsdManager.unregisterService(it) } }
        raopListener = null
        airplayListener = null
    }

    /** Raw DNS TXT record bytes, also returned by `GET /info` as `txtAirPlay`. */
    fun airplayTxtRecord(): ByteArray = encodeTxt(airplayTxt())

    fun raopTxtRecord(): ByteArray = encodeTxt(raopTxt())

    private fun registerRaop() {
        val info = NsdServiceInfo().apply {
            serviceName = "${macNoSeparators()}@$deviceName"
            serviceType = "_raop._tcp"
            // 必须显式限定外层属性：apply 里的裸 `port` 会解析成
            // NsdServiceInfo 自己的 port（默认 0），导致注册报 Invalid port number。
            setPort(this@AirPlayMdns.port)
            raopTxt().forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceName} ${info.serviceType} on ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "raop registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        raopListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "raop register threw", it) }
    }

    private fun registerAirplay() {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp"
            setPort(this@AirPlayMdns.port)
            airplayTxt().forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "registered ${info.serviceName} ${info.serviceType} on ${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "airplay registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        airplayListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "airplay register threw", it) }
    }

    private fun raopTxt(): LinkedHashMap<String, String> = raopTxtMap()

    private fun airplayTxt(): LinkedHashMap<String, String> = airplayTxtMap(deviceId)

    private fun macNoSeparators(): String =
        deviceId.replace(":", "").replace("-", "").uppercase().padEnd(12, '0').take(12)

    companion object {
        private const val TAG = "AirPlayMdns"
        const val MODEL = "AppleTV3,2"
        const val SRCVERS = "220.68"

        /**
         * 64-bit AirPlay feature mask. Bit 27 (supports legacy pairing) is
         * cleared so senders skip pair-setup/pair-verify; mirroring still uses
         * the FairPlay SAP handshake, which is independent of pairing.
         */
        const val FEATURES = 0x1E527FFFF7L
        /**
         * AirPlay TXT uses `low32,high32`. Bit 27 of the low word (legacy
         * pairing) is cleared so senders skip pair-setup; mirroring still uses
         * the FairPlay SAP handshake.
         */
        const val FEATURES_STRING = "0x527FFFF7,0x1E"
        const val PUBLIC_KEY_HEX = "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7"
        const val DEVICE_UUID = "2e388006-13ba-4041-9a67-25dd4a43d536"

        fun raopTxtMap(): LinkedHashMap<String, String> = linkedMapOf(
            "txtvers" to "1",
            "ch" to "2",
            "cn" to "0,1,2,3",
            "et" to "0,3,5",
            "vv" to "2",
            "ft" to FEATURES_STRING,
            "am" to MODEL,
            "md" to "0,1,2",
            "rhd" to "5.6.0.0",
            "pw" to "false",
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "tp" to "UDP",
            "sf" to "0x4",
            "vs" to SRCVERS,
            "vn" to "65537",
            "pk" to PUBLIC_KEY_HEX,
            "da" to "true",
            "ek" to "1",
        )

        fun airplayTxtMap(deviceId: String): LinkedHashMap<String, String> = linkedMapOf(
            "deviceid" to deviceId.lowercase(),
            "features" to FEATURES_STRING,
            "flags" to "0x4",
            "model" to MODEL,
            "pk" to PUBLIC_KEY_HEX,
            "pi" to DEVICE_UUID,
            "srcvers" to SRCVERS,
            "vv" to "2",
            "pw" to "false",
        )

        fun encodeTxt(txt: Map<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((k, v) in txt) {
                val bytes = "$k=$v".toByteArray(Charsets.UTF_8)
                out.write(bytes.size and 0xFF)
                out.write(bytes)
            }
            return out.toByteArray()
        }

        fun airplayTxtRecord(deviceId: String): ByteArray = encodeTxt(airplayTxtMap(deviceId))
        fun raopTxtRecord(): ByteArray = encodeTxt(raopTxtMap())
    }
}
