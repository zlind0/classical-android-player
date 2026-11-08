package com.aurora.music.data.cast.receiver.airplay

enum class AirPlayCodec { ALAC, AAC, AAC_ELD, PCM, UNKNOWN }

/** ALAC magic-cookie fields, in the order RAOP puts them into `a=fmtp`. */
data class AlacConfig(
    val frameLength: Int,
    val compatibleVersion: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val maxRun: Int,
    val maxFrameBytes: Int,
    val avgBitRate: Int,
    val sampleRate: Int,
)

data class AirPlayAudioFormat(
    val codec: AirPlayCodec,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val payloadType: Int,
    val aesKey: ByteArray,
    val aesIv: ByteArray,
    val alacConfig: AlacConfig? = null,
    /** `config=` hex from mpeg4-generic fmtp (AudioSpecificConfig). */
    val aacConfig: ByteArray? = null,
    val ptimeMs: Int = 0,
    val isMirroring: Boolean = false,
    /** false for `et=0` senders: payload is plain RTP data, do not AES-decrypt. */
    val encrypted: Boolean = true,
)

/**
 * Parser for the RAOP `ANNOUNCE` SDP body.
 *
 * Only the audio stream is parsed here; mirroring sessions carry an extra
 * `streams` binary plist inside SETUP, not SDP.
 */
object AirPlaySdp {

    fun parse(sdp: String): AirPlayAudioFormat? {
        var payloadType = 96
        var rtpmap = ""
        var fmtp = ""
        var rsaaeskey: ByteArray? = null
        var aesiv: ByteArray? = null
        var ptimeMs = 0

        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("m=audio") -> {
                    // m=audio 0 RTP/AVP 96
                    payloadType = line.substringAfter("RTP/AVP", "").trim().substringBefore(' ')
                        .toIntOrNull() ?: payloadType
                }
                line.startsWith("a=rtpmap:") -> {
                    val rest = line.substringAfter("a=rtpmap:")
                    val pt = rest.substringBefore(' ').toIntOrNull()
                    if (pt == payloadType) rtpmap = rest.substringAfter(' ', "").trim()
                }
                line.startsWith("a=fmtp:") -> {
                    val rest = line.substringAfter("a=fmtp:")
                    val pt = rest.substringBefore(' ').toIntOrNull()
                    if (pt == payloadType) fmtp = rest.substringAfter(' ', "").trim()
                }
                line.startsWith("a=rsaaeskey:") -> {
                    rsaaeskey = AirPlayCrypto.decodeBase64(line.substringAfter("a=rsaaeskey:"))
                }
                line.startsWith("a=aesiv:") -> {
                    aesiv = AirPlayCrypto.decodeBase64(line.substringAfter("a=aesiv:"))
                }
                line.startsWith("a=ptime:") -> {
                    ptimeMs = line.substringAfter("a=ptime:").trim().toDoubleOrNull()?.toInt() ?: 0
                }
            }
        }

        val aesKey = rsaaeskey?.let { AirPlayCrypto.decryptRaopAesKey(it) }
        val iv = aesiv ?: ByteArray(16)
        if (aesKey == null) {
            // Unencrypted sessions are legal but rare; RAOP senders with et=0
            // exist. Treat a missing key as all-zero so decoding still works.
        }
        val key = aesKey ?: ByteArray(16)

        val lowerMap = rtpmap.lowercase()
        return when {
            lowerMap.startsWith("applelossless") -> {
                val values = fmtp.split(' ').mapNotNull { it.toIntOrNull() }
                if (values.size < 11) {
                    null
                } else {
                    AirPlayAudioFormat(
                        codec = AirPlayCodec.ALAC,
                        sampleRate = values[10],
                        channels = values[6].coerceIn(1, 2),
                        bitDepth = values[2].coerceIn(16, 32),
                        payloadType = payloadType,
                        aesKey = key,
                        aesIv = iv,
                        encrypted = aesKey != null,
                        alacConfig = AlacConfig(
                            frameLength = values[0],
                            compatibleVersion = values[1],
                            bitDepth = values[2],
                            pb = values[3],
                            mb = values[4],
                            kb = values[5],
                            channels = values[6],
                            maxRun = values[7],
                            maxFrameBytes = values[8],
                            avgBitRate = values[9],
                            sampleRate = values[10],
                        ),
                        ptimeMs = ptimeMs,
                    )
                }
            }
            lowerMap.startsWith("mpeg4-generic") -> {
                val rate = lowerMap.substringAfter('/').substringBefore('/').toIntOrNull() ?: 44100
                val channels = lowerMap.split('/').getOrNull(2)?.toIntOrNull() ?: 2
                val configHex = Regex("config=([0-9a-fA-F]+)").find(fmtp)?.groupValues?.get(1)
                AirPlayAudioFormat(
                    codec = AirPlayCodec.AAC,
                    sampleRate = rate,
                    channels = channels.coerceIn(1, 2),
                    bitDepth = 16,
                    payloadType = payloadType,
                    aesKey = key,
                    aesIv = iv,
                    encrypted = aesKey != null,
                    aacConfig = configHex?.let(::hexToBytes),
                    ptimeMs = ptimeMs,
                )
            }
            lowerMap.startsWith("l16") -> AirPlayAudioFormat(
                codec = AirPlayCodec.PCM,
                sampleRate = lowerMap.substringAfter('/').substringBefore('/').toIntOrNull() ?: 44100,
                channels = lowerMap.split('/').getOrNull(2)?.toIntOrNull()?.coerceIn(1, 2) ?: 2,
                bitDepth = 16,
                payloadType = payloadType,
                aesKey = key,
                aesIv = iv,
                encrypted = aesKey != null,
                ptimeMs = ptimeMs,
            )
            else -> {
                null
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
