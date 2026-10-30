package com.aurora.music.data.cast

/** 局域网里一台可投屏的设备。浏览器是本地合成的一条，不走发现协议。 */
enum class CastDeviceKind { APP, BROWSER }

data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val kind: CastDeviceKind = CastDeviceKind.APP,
    /** mdns / udp / manual / browser */
    val source: String = "mdns",
    val lastSeen: Long = 0L,
) {
    val baseUrl: String get() = "http://$host:$port"
}

/** 投屏内容：音乐用原生播放页，电子书沿用网页排版。 */
enum class CastContentType { MUSIC, EBOOK }

/** 接收端收到的投屏请求（由发送端 POST /cast/offer 推过来）。 */
data class CastOffer(
    val senderId: String = "",
    val senderName: String = "",
    val host: String = "",
    val port: Int = 0,
    /** music / ebook */
    val type: String = "music",
    val title: String = "",
    val subtitle: String = "",
    val url: String = "",
) {
    val baseUrl: String
        get() = url.trimEnd('/').ifBlank { "http://$host:$port" }

    val contentType: CastContentType
        get() = if (type == "ebook") CastContentType.EBOOK else CastContentType.MUSIC

    val valid: Boolean
        get() = url.startsWith("http") || (host.isNotBlank() && port > 0)
}

/** 发送端当前的投屏目标（投屏选择页据此显示“等待接受 / 已连接”）。 */
data class CastSenderState(
    val device: CastDevice? = null,
    val contentType: CastContentType = CastContentType.MUSIC,
    val waiting: Boolean = false,
    val connected: Boolean = false,
) {
    val active: Boolean get() = device != null
}

/** 接收端发回发送端的控制指令。 */
enum class CastCommandType {
    TOGGLE, PLAY, PAUSE, NEXT, PREV, JUMP, SEEK, SHUFFLE, REPEAT, LIKE, REJECT, STOP
}

data class CastCommand(
    val type: CastCommandType,
    val index: Int = -1,
    val positionMs: Long = 0L,
)
