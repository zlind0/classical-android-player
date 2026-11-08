package com.aurora.music.data.cast.receiver.airplay

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * AirPlay 1 RTSP + HTTP server.
 *
 * One TCP port serves both discovery (`GET /info`), FairPlay (`POST /fp-setup`),
 * RAOP session negotiation (`ANNOUNCE` / `SETUP` / `RECORD`) and metadata
 * (`SET_PARAMETER`). Apple senders use the same port for their event channel,
 * so every accepted connection runs the same request loop.
 */
class AirPlayRtspServer(
    private val deviceName: String,
    private val deviceId: String,
    private val preferredPort: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun displaySize(): Pair<Int, Int>
        fun onSenderIdentified(name: String, mirroring: Boolean)
        fun onAudioStream(format: AirPlayAudioFormat, data: DatagramSocket, control: DatagramSocket)
        fun onMirrorStream(streamConnectionId: Long, aesKey: ByteArray, aesIv: ByteArray): Int
        fun onRecord()
        fun onFlush()
        fun onTeardown()
        fun onMetadata(nowPlaying: AirPlayNowPlaying)
        fun onVolume(volume: Float)
        fun onProgress(positionMs: Long, durationMs: Long)
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connections = ConcurrentHashMap<Socket, Connection>()
    private val random = SecureRandom()

    @Volatile private var session: Session? = null

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return port
        val socket = bindServer()
        serverSocket = socket
        acceptThread = Thread({ acceptLoop() }, "AirPlayRtspAccept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "AirPlay RTSP listening on ${socket.localPort}")
        return socket.localPort
    }

    private fun bindServer(): ServerSocket {
        for (p in preferredPort..(preferredPort + 10)) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (_: Exception) {
            }
        }
        return ServerSocket(0) // ephemeral fallback
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.keys.toList().forEach { runCatching { it.close() } }
        connections.clear()
        session?.close()
        session = null
        acceptThread?.join(400)
        acceptThread = null
    }

    /** End the negotiated session but keep listening for the next sender. */
    fun stopSession() {
        endSession()
        connections.keys.toList().forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val connection = Connection(socket)
            connections[socket] = connection
            Thread({ connection.run() }, "AirPlayRtspConn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun sessionLocked(create: Boolean): Session? {
        session?.let { return it }
        if (!create) return null
        val s = Session()
        session = s
        return s
    }

    private fun endSession() {
        val s = session ?: return
        session = null
        s.close()
        listener.onTeardown()
    }

    /** One negotiated RAOP session; audio/video sockets live here. */
    private inner class Session {
        val id: String = buildString {
            repeat(16) { append("0123456789ABCDEF"[random.nextInt(16)]) }
        }
        var audioFormat: AirPlayAudioFormat? = null
        var aesKey: ByteArray? = null
        var aesIv: ByteArray? = null
        var announced = false
        var mirroring = false
        var streamConnectionId = 0L
        var owner: Connection? = null
        var audioData: DatagramSocket? = null
        var audioControl: DatagramSocket? = null
        var timing: DatagramSocket? = null
        var started = false

        fun close() {
            runCatching { audioData?.close() }
            runCatching { audioControl?.close() }
            runCatching { timing?.close() }
            audioData = null
            audioControl = null
            timing = null
        }
    }

    private inner class Connection(private val socket: Socket) {
        private var fairplay: FairPlayBridge? = null
        private var senderName = "AirPlay 设备"
        fun run() {
            socket.soTimeout = 0
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (running.get()) {
                    val request = readRequest(input) ?: break
                    handle(request, output)
                }
            } catch (_: Exception) {
            } finally {
                fairplay?.close()
                fairplay = null
                if (session?.owner === this) {
                    runCatching { endSession() }
                }
                connections.remove(socket)
                runCatching { socket.close() }
            }
        }

        private fun handle(request: RtspRequest, output: OutputStream) {
            when {
                request.method == "GET" && request.url.startsWith("/info") -> respondInfo(request, output)
                request.method == "POST" && request.url.startsWith("/fp-setup") -> respondFpSetup(request, output)
                request.method == "POST" && request.url.startsWith("/pair-setup") ->
                    respondEmpty(request, output)
                request.method == "POST" && request.url.startsWith("/pair-verify") ->
                    respondEmpty(request, output)
                request.method == "OPTIONS" -> respondOptions(request, output)
                request.method == "ANNOUNCE" -> handleAnnounce(request, output)
                request.method == "SETUP" -> handleSetup(request, output)
                request.method == "RECORD" -> {
                    val session = sessionLocked(create = false)
                    if (session != null && !session.started) {
                        session.started = true
                        listener.onRecord()
                    }
                    respond(
                        request, output, 200, "OK", null, ByteArray(0),
                        extraHeaders = linkedMapOf(
                            "Audio-Latency" to "11025",
                            "Audio-Jack-Status" to "connected; type=analog",
                        ),
                    )
                }
                request.method == "SET_PARAMETER" -> handleSetParameter(request, output)
                request.method == "GET_PARAMETER" -> respondGetParameter(request, output)
                request.method == "FLUSH" -> {
                    listener.onFlush()
                    session?.let { flushAudio(it) }
                    respondEmpty(request, output)
                }
                request.method == "TEARDOWN" -> {
                    endSession()
                    respondEmpty(request, output, close = true)
                }
                request.method == "POST" && request.url.startsWith("/feedback") -> respondEmpty(request, output)
                request.method == "POST" && request.url.startsWith("/command") -> respondEmpty(request, output)
                request.method == "POST" && request.url.startsWith("/photo") -> respondEmpty(request, output)
                else -> {
                    Log.d(TAG, "unhandled ${request.method} ${request.url}")
                    respondEmpty(request, output)
                }
            }
        }

        private fun flushAudio(session: Session) {
            // The jitter buffer is owned by AirPlayReceiver; onFlush() clears it
            // together with the audio sink.
            session.audioData ?: return
        }

        private fun handleAnnounce(request: RtspRequest, output: OutputStream) {
            val sdp = String(request.body, Charsets.UTF_8)
            val format = AirPlaySdp.parse(sdp)
            val existing = sessionLocked(create = false)
            if (existing != null && (existing.started || existing.mirroring || existing.announced)) {
                // A new sender replaces the previous session.
                endSession()
            }
            val active = sessionLocked(create = true)!!
            active.owner = this
            active.audioFormat = format
            format?.let {
                active.aesKey = it.aesKey
                active.aesIv = it.aesIv
            }
            active.announced = true
            senderName = request.senderName() ?: senderName
            listener.onSenderIdentified(senderName, mirroring = false)
            respondEmpty(request, output)
        }

        private fun handleSetup(request: RtspRequest, output: OutputStream) {
            val active = sessionLocked(create = true)!!
            active.owner = this
            val body = request.body
            val root = if (body.isNotEmpty()) {
                runCatching { AppleBinaryPlist.decode(body) as? Map<*, *> }.getOrNull()
            } else {
                null
            }

            // First SETUP: FairPlay keys for mirroring.
            val eiv = root?.get("eiv") as? ByteArray
            val ekey = root?.get("ekey") as? ByteArray
            if (eiv != null && ekey != null) {
                val fp = fairplay ?: FairPlayBridge().also { fairplay = it }
                val key = fp.decryptKey(ekey)
                if (key != null) {
                    val ivCopy = eiv.copyOf(16)
                    active.aesKey = key
                    active.aesIv = ivCopy
                    active.audioFormat = active.audioFormat?.copy(
                        aesKey = key,
                        aesIv = ivCopy,
                        encrypted = true,
                    )
                    Log.i(TAG, "FairPlay AES key installed")
                } else {
                    Log.w(TAG, "FairPlay ekey decryption failed")
                }
            }

            val timingSocket = runCatching { DatagramSocket(0) }.getOrNull()
            active.timing?.let { runCatching { it.close() } }
            active.timing = timingSocket

            var mirrorPort = 0
            val responseStreams = ArrayList<Any?>()
            val streams = root?.get("streams") as? List<*>
            if (streams != null) {
                for (entry in streams) {
                    val stream = entry as? Map<*, *> ?: continue
                    when ((stream["type"] as? Number)?.toInt() ?: -1) {
                        110 -> {
                            val id = (stream["streamConnectionID"] as? Number)?.toLong() ?: 0L
                            val key = active.aesKey
                            val iv = active.aesIv
                            if (key != null && iv != null) {
                                active.mirroring = true
                                active.streamConnectionId = id
                                mirrorPort = listener.onMirrorStream(id, key, iv)
                                if (!active.announced) {
                                    active.announced = true
                                    senderName = request.senderName() ?: senderName
                                    listener.onSenderIdentified(senderName, mirroring = true)
                                }
                            } else {
                                Log.w(TAG, "mirroring SETUP without FairPlay key")
                            }
                            responseStreams.add(
                                plistDict(
                                    "type" to 110L,
                                    "dataPort" to mirrorPort.toLong(),
                                    "controlPort" to mirrorPort.toLong(),
                                    "streamConnectionID" to id,
                                ),
                            )
                        }
                        96 -> {
                            val ct = (stream["ct"] as? Number)?.toInt() ?: 2
                            val spf = (stream["spf"] as? Number)?.toInt() ?: 0
                            val format = active.audioFormat?.let { audio ->
                                audio.copy(
                                    aesKey = active.aesKey ?: audio.aesKey,
                                    aesIv = active.aesIv ?: audio.aesIv,
                                    encrypted = active.aesKey != null || audio.encrypted,
                                )
                            } ?: mirrorAudioFormat(active, ct, spf)
                            val sockets = createAudioSockets()
                            if (sockets != null) {
                                active.audioFormat = format
                                active.audioData = sockets.first
                                active.audioControl = sockets.second
                                listener.onAudioStream(format, sockets.first, sockets.second)
                                responseStreams.add(
                                    plistDict(
                                        "type" to 96L,
                                        "dataPort" to sockets.first.localPort.toLong(),
                                        "controlPort" to sockets.second.localPort.toLong(),
                                    ),
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
            if (!active.announced) {
                active.announced = true
                senderName = request.senderName() ?: senderName
                listener.onSenderIdentified(senderName, mirroring = active.mirroring)
            }

            // Classic RAOP (audio-only) SETUP carries ports in the Transport
            // header instead of a binary-plist body.
            val transport = request.headers["transport"]
            if (streams == null && transport != null) {
                val ports = parseTransport(transport)
                val sockets = createAudioSockets()
                if (sockets != null) {
                    val announced = active.audioFormat
                    val format = announced?.copy(
                        aesKey = active.aesKey ?: announced.aesKey,
                        aesIv = active.aesIv ?: announced.aesIv,
                        encrypted = active.aesKey != null || announced.encrypted,
                    ) ?: defaultAlacFormat(active)
                    active.audioFormat = format
                    active.audioData = sockets.first
                    active.audioControl = sockets.second
                    listener.onAudioStream(format, sockets.first, sockets.second)
                    val transportResponse =
                        "RTP/AVP/UDP;unicast;mode=record" +
                            ";timing_port=${active.timing?.localPort ?: ports.second};events" +
                            ";control_port=${sockets.second.localPort}" +
                            ";server_port=${sockets.first.localPort}"
                    respond(
                        request, output, 200, "OK", null, ByteArray(0),
                        extraHeaders = linkedMapOf(
                            "Transport" to transportResponse,
                            "Session" to active.id,
                            "Audio-Jack-Status" to "connected; type=analog",
                        ),
                    )
                    return
                }
            }

            val response = plistDict(
                "timingPort" to (active.timing?.localPort ?: 0).toLong(),
                "eventPort" to port.toLong(),
                "streams" to responseStreams,
            )
            respondPlist(request, output, response)
        }

        private fun parseTransport(transport: String): Pair<Int, Int> {
            val control = Regex("control_port=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val timing = Regex("timing_port=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return control to timing
        }

        private fun defaultAlacFormat(active: Session): AirPlayAudioFormat = AirPlayAudioFormat(
            codec = AirPlayCodec.ALAC,
            sampleRate = 44100,
            channels = 2,
            bitDepth = 16,
            payloadType = 96,
            aesKey = active.aesKey ?: ByteArray(16),
            aesIv = active.aesIv ?: ByteArray(16),
            encrypted = active.aesKey != null,
            alacConfig = AlacConfig(352, 0, 16, 40, 10, 14, 2, 255, 0, 0, 44100),
        )

        private fun mirrorAudioFormat(active: Session, ct: Int, spf: Int): AirPlayAudioFormat {
            val key = active.aesKey ?: ByteArray(16)
            val iv = active.aesIv ?: ByteArray(16)
            return when (ct) {
                2 -> AirPlayAudioFormat(
                    codec = AirPlayCodec.ALAC,
                    sampleRate = 44100,
                    channels = 2,
                    bitDepth = 16,
                    payloadType = 96,
                    aesKey = key,
                    aesIv = iv,
                    encrypted = active.aesKey != null,
                    alacConfig = AlacConfig(
                        frameLength = if (spf > 0) spf else 352,
                        compatibleVersion = 0,
                        bitDepth = 16,
                        pb = 40,
                        mb = 10,
                        kb = 14,
                        channels = 2,
                        maxRun = 255,
                        maxFrameBytes = 0,
                        avgBitRate = 0,
                        sampleRate = 44100,
                    ),
                )
                8 -> AirPlayAudioFormat(
                    codec = AirPlayCodec.AAC_ELD,
                    sampleRate = 44100,
                    channels = 2,
                    bitDepth = 16,
                    payloadType = 96,
                    aesKey = key,
                    aesIv = iv,
                    encrypted = active.aesKey != null,
                )
                else -> AirPlayAudioFormat(
                    codec = AirPlayCodec.AAC,
                    sampleRate = 44100,
                    channels = 2,
                    bitDepth = 16,
                    payloadType = 96,
                    aesKey = key,
                    aesIv = iv,
                    encrypted = active.aesKey != null,
                )
            }
        }

        private fun createAudioSockets(): Pair<DatagramSocket, DatagramSocket>? {
            val data = runCatching { DatagramSocket(0) }.getOrNull() ?: return null
            val control = runCatching { DatagramSocket(0) }.getOrNull() ?: run {
                runCatching { data.close() }
                return null
            }
            data.receiveBufferSize = 512 * 1024
            control.receiveBufferSize = 64 * 1024
            return data to control
        }

        private fun handleSetParameter(request: RtspRequest, output: OutputStream) {
            val contentType = request.headers["content-type"] ?: ""
            when {
                contentType.contains("text/parameters", ignoreCase = true) -> {
                    val text = String(request.body, Charsets.UTF_8)
                    val parsed = AirPlayMetadata.parseTextParameters(text)
                    if (parsed != null) {
                        var metadata = parsed
                        val session = sessionLocked(create = false)
                        val sampleRate = session?.audioFormat?.sampleRate ?: 44100
                        for (line in text.lineSequence()) {
                            val key = line.substringBefore(':', "").trim()
                            val value = line.substringAfter(':', "").trim()
                            when (key) {
                                "volume" -> value.toDoubleOrNull()?.let {
                                    val linear = AirPlayMetadata.dbToLinear(it)
                                    Log.i(TAG, "volume: $it dB -> $linear")
                                    listener.onVolume(linear)
                                }
                                "progress" -> {
                                    val parts = value.split('/')
                                    if (parts.size == 3) {
                                        val start = parts[0].trim().toLongOrNull() ?: 0
                                        val current = parts[1].trim().toLongOrNull() ?: 0
                                        val end = parts[2].trim().toLongOrNull() ?: 0
                                        listener.onProgress(
                                            (current - start) * 1000L / sampleRate,
                                            (end - start) * 1000L / sampleRate,
                                        )
                                    }
                                }
                            }
                        }
                        if (metadata.title.isNotEmpty() || metadata.artist.isNotEmpty() ||
                            metadata.album.isNotEmpty()
                        ) {
                            listener.onMetadata(metadata)
                        }
                    }
                }
                contentType.contains("x-dmap-tagged", ignoreCase = true) -> {
                    val parsed = AirPlayMetadata.parseDmap(request.body)
                    if (parsed != null) listener.onMetadata(parsed)
                }
                contentType.contains("image/jpeg", ignoreCase = true) ||
                    contentType.contains("image/png", ignoreCase = true) -> {
                    listener.onMetadata(AirPlayNowPlaying(artwork = request.body))
                }
                contentType.contains("x-apple-binary-plist", ignoreCase = true) -> {
                    // AirPlay 2 style SET_PARAMETER; log only.
                    Log.d(TAG, "binary plist SET_PARAMETER (${request.body.size} bytes)")
                }
            }
            respondEmpty(request, output)
        }

        private fun respondGetParameter(request: RtspRequest, output: OutputStream) {
            val contentType = request.headers["content-type"] ?: ""
            if (contentType.contains("text/parameters", ignoreCase = true)) {
                val text = String(request.body, Charsets.UTF_8)
                val body = if (text.contains("volume")) "volume: -20.0\r\n" else ""
                respond(
                    request, output, status = 200, reason = "OK",
                    contentType = "text/parameters",
                    body = body.toByteArray(Charsets.UTF_8),
                )
            } else {
                respondEmpty(request, output)
            }
        }

        private fun respondFpSetup(request: RtspRequest, output: OutputStream) {
            val fp = fairplay ?: FairPlayBridge().also { fairplay = it }
            val reply = when (request.body.size) {
                16 -> fp.setup(request.body)
                164 -> fp.handshake(request.body)
                else -> null
            }
            if (reply == null) {
                Log.w(TAG, "fp-setup failed for ${request.body.size}-byte body")
                respond(request, output, 200, "OK", "application/octet-stream", ByteArray(0))
            } else {
                respond(request, output, 200, "OK", "application/octet-stream", reply)
            }
        }

        private fun respondOptions(request: RtspRequest, output: OutputStream) {
            val public =
                "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            respond(
                request, output, 200, "OK", null, ByteArray(0),
                extraHeaders = linkedMapOf("Public" to public),
            )
        }

        private fun respondInfo(request: RtspRequest, output: OutputStream) {
            val txtAir = AirPlayMdns.airplayTxtRecord(deviceId)
            val txtRaop = AirPlayMdns.raopTxtRecord()
            val (screenWidth, screenHeight) = listener.displaySize()
            val pk = hexToBytes(AirPlayMdns.PUBLIC_KEY_HEX)
            val info = plistDict(
                "deviceID" to deviceId.lowercase(),
                "macAddress" to deviceId.lowercase(),
                "features" to AirPlayMdns.FEATURES,
                "flags" to 0x4L,
                "model" to AirPlayMdns.MODEL,
                "name" to deviceName,
                "pk" to pk,
                "pi" to AirPlayMdns.DEVICE_UUID,
                "srcvers" to AirPlayMdns.SRCVERS,
                "vv" to 2L,
                "statusFlags" to 68L,
                "keepAliveLowPower" to true,
                "keepAliveSendStatsAsBody" to true,
                "sourceVersion" to AirPlayMdns.SRCVERS,
                "initialVolume" to 1.0,
                "txtAirPlay" to txtAir,
                "txtRAOP" to txtRaop,
                "audioFormats" to plistArray(
                    plistDict(
                        "type" to 100L,
                        "audioInputFormats" to 0x3fffffcL,
                        "audioOutputFormats" to 0x3fffffcL,
                    ),
                    plistDict(
                        "type" to 101L,
                        "audioInputFormats" to 0x3fffffcL,
                        "audioOutputFormats" to 0x3fffffcL,
                    ),
                ),
                "audioLatencies" to plistArray(
                    plistDict(
                        "type" to 100L,
                        "audioType" to "default",
                        "inputLatencyMicros" to 0L,
                        "outputLatencyMicros" to 0L,
                    ),
                    plistDict(
                        "type" to 101L,
                        "audioType" to "default",
                        "inputLatencyMicros" to 0L,
                        "outputLatencyMicros" to 0L,
                    ),
                ),
                "displays" to plistArray(
                    plistDict(
                        "uuid" to AirPlayMdns.DEVICE_UUID,
                        "widthPhysical" to 0L,
                        "heightPhysical" to 0L,
                        "width" to screenWidth.toLong(),
                        "height" to screenHeight.toLong(),
                        "widthPixels" to screenWidth.toLong(),
                        "heightPixels" to screenHeight.toLong(),
                        "rotation" to false,
                        "refreshRate" to (1.0 / 60.0),
                        "maxFPS" to 60L,
                        "overscanned" to false,
                        "features" to 14L,
                    ),
                ),
            )
            respondPlist(request, output, info)
        }

        private fun respondPlist(request: RtspRequest, output: OutputStream, plist: Any?) {
            val body = AppleBinaryPlist.encode(plist)
            respond(
                request, output, 200, "OK",
                "application/x-apple-binary-plist", body,
            )
        }

        private fun respondEmpty(request: RtspRequest, output: OutputStream, close: Boolean = false) {
            respond(
                request, output, 200, "OK", null, ByteArray(0),
                extraHeaders = if (close) linkedMapOf("Connection" to "close") else emptyMap(),
            )
        }

        private fun respond(
            request: RtspRequest,
            output: OutputStream,
            status: Int,
            reason: String,
            contentType: String?,
            body: ByteArray,
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            val builder = StringBuilder()
            builder.append("RTSP/1.0 ").append(status).append(' ').append(reason).append("\r\n")
            builder.append("CSeq: ").append(request.cseq ?: "0").append("\r\n")
            builder.append("Server: AirTunes/").append(AirPlayMdns.SRCVERS).append("\r\n")
            contentType?.let { builder.append("Content-Type: ").append(it).append("\r\n") }
            for ((k, v) in extraHeaders) builder.append(k).append(": ").append(v).append("\r\n")
            builder.append("Content-Length: ").append(body.size).append("\r\n\r\n")
            val header = builder.toString().toByteArray(Charsets.US_ASCII)
            runCatching {
                output.write(header)
                if (body.isNotEmpty()) output.write(body)
                output.flush()
            }
        }
    }

    private fun readRequest(input: InputStream): RtspRequest? {
        val headerBytes = readHeaderBlock(input) ?: return null
        val text = String(headerBytes, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(' ')
        if (requestLine.size < 3) return null
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return RtspRequest(
            method = requestLine[0],
            url = requestLine[1],
            headers = headers,
            body = body,
            cseq = headers["cseq"],
        )
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val tail = IntArray(4)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            tail[0] = tail[1]
            tail[1] = tail[2]
            tail[2] = tail[3]
            tail[3] = b
            if (tail[0] == 13 && tail[1] == 10 && tail[2] == 13 && tail[3] == 10) {
                return out.toByteArray()
            }
        }
    }

    private data class RtspRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val cseq: String?,
    ) {
        fun senderName(): String? =
            headers["x-apple-device-name"]
                ?: headers["x-apple-client-name"]
                ?: headers["client-name"]
                ?: headers["user-agent"]?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    companion object {
        private const val TAG = "AirPlayRtsp"
    }
}
