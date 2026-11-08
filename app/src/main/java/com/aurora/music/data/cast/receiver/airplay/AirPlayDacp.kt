package com.aurora.music.data.cast.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * AirPlay reverse control (DACP, Digital Audio Control Protocol).
 *
 * Ported from the reference receiver. Once a sender advertises `DACP-ID` and
 * `Active-Remote` in its RTSP request headers we resolve the matching
 * `iTunes_Ctrl_<id>` service over mDNS and can then drive the *sender*:
 * play/pause, next/previous track, volume and fast-forward/rewind.
 */
class AirPlayDacp(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit = {},
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var dacpId = ""
    @Volatile private var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0
    private var lastResolveMs = 0L

    val isReady: Boolean get() = host.isNotEmpty() && activeRemote.isNotEmpty()

    @Synchronized
    fun update(dacpId: String, activeRemote: String) {
        if (dacpId.isBlank() || activeRemote.isBlank()) return
        val same = dacpId == this.dacpId && activeRemote == this.activeRemote
        if (same && (isReady || System.currentTimeMillis() - lastResolveMs < RETRY_INTERVAL_MS)) return
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        host = ""
        port = 0
        lastResolveMs = System.currentTimeMillis()
        resolve()
    }

    fun play() = send("/ctrl-int/1/play")
    fun pause() = send("/ctrl-int/1/pause")
    fun nextItem() = send("/ctrl-int/1/nextitem")
    fun previousItem() = send("/ctrl-int/1/previtem")
    fun volumeUp() = send("/ctrl-int/1/volumeup")
    fun volumeDown() = send("/ctrl-int/1/volumedown")
    fun beginFastForward() = send("/ctrl-int/1/beginff")
    fun beginRewind() = send("/ctrl-int/1/beginrew")
    fun playResume() = send("/ctrl-int/1/playresume")

    @Synchronized
    fun reset() {
        val wasReady = isReady
        dacpId = ""
        activeRemote = ""
        host = ""
        port = 0
        lastResolveMs = 0L
        if (wasReady) onReadyChanged(false)
    }

    fun release() {
        reset()
        executor.shutdownNow()
    }

    private fun resolve() {
        if (dacpId.isEmpty()) return
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            serviceName = "iTunes_Ctrl_$dacpId"
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "DACP resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val address = serviceInfo.host?.hostAddress ?: return
                    host = address
                    port = serviceInfo.port
                    Log.i(TAG, "DACP resolved: $host:$port")
                    onReadyChanged(true)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
        }
    }

    private fun send(path: String) {
        if (!isReady) return
        val h = host
        val p = port
        val remote = activeRemote
        try {
            executor.execute {
                try {
                    val url = URL("http://$h:$p$path")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", remote)
                    conn.setRequestProperty("Host", "$h:$p")
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val code = conn.responseCode
                    runCatching { conn.inputStream.readBytes() }
                    conn.disconnect()
                    if (code !in 200..299) Log.w(TAG, "DACP $path -> HTTP $code")
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DACP enqueue failed: $path", e)
        }
    }

    companion object {
        private const val TAG = "AirPlayDacp"
        private const val RETRY_INTERVAL_MS = 5000L
    }
}
