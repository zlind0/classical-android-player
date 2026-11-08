package com.aurora.music.data.cast.receiver.airplay

import android.util.Log

/**
 * JNI wrapper for the FairPlay SAP handshake required by AirPlay screen
 * mirroring (shairplay/playfair, GPLv3 -- isolated in `libaurora_fairplay.so`).
 *
 * Flow:
 *  1. `POST /fp-setup` 16-byte body -> [setup] returns 142 bytes.
 *  2. `POST /fp-setup` 164-byte body -> [handshake] returns 32 bytes.
 *  3. SETUP's `ekey` (72 bytes) -> [decryptKey] returns the 16-byte AES key.
 */
class FairPlayBridge : AutoCloseable {

    private var handle: Long = 0L

    init {
        if (loaded) {
            handle = nativeCreate()
            if (handle == 0L) Log.w(TAG, "FairPlay context allocation failed")
        } else {
            Log.w(TAG, "aurora_fairplay library unavailable")
        }
    }

    val isAvailable: Boolean get() = handle != 0L

    fun setup(request: ByteArray): ByteArray? {
        val h = handle
        if (h == 0L || request.size != 16) return null
        return runCatching { nativeSetup(h, request) }.getOrNull()
    }

    fun handshake(request: ByteArray): ByteArray? {
        val h = handle
        if (h == 0L || request.size != 164) return null
        return runCatching { nativeHandshake(h, request) }.getOrNull()
    }

    fun decryptKey(ekey: ByteArray): ByteArray? {
        val h = handle
        if (h == 0L || ekey.size != 72) return null
        return runCatching { nativeDecrypt(h, ekey) }.getOrNull()
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeDestroy(h) }
    }

    private external fun nativeCreate(): Long
    private external fun nativeSetup(handle: Long, request: ByteArray): ByteArray?
    private external fun nativeHandshake(handle: Long, request: ByteArray): ByteArray?
    private external fun nativeDecrypt(handle: Long, ekey: ByteArray): ByteArray?
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "AirPlayFairPlay"
        private val loaded: Boolean = runCatching { System.loadLibrary("aurora_fairplay"); true }
            .getOrDefault(false)
    }
}
