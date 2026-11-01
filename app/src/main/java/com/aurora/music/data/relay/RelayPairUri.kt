package com.aurora.music.data.relay

/**
 * 解析 NAS 面板显示的配对码：`podrelay://pair?v=2&h=<LAN IP>&p=<port>&t=<token>&n=<name>&id=<deviceId>`。
 * 纯字符串解析，方便 JVM 单测；不依赖 android.net.Uri。
 */
data class RelayPairUri(
    val host: String,
    val port: Int,
    val token: String,
    val deviceName: String,
    val deviceId: String,
) {
    companion object {
        const val SCHEME_PREFIX = "podrelay://pair"

        fun parse(raw: String?): RelayPairUri? {
            val text = raw?.trim().orEmpty()
            if (!text.startsWith(SCHEME_PREFIX, ignoreCase = true)) return null
            val query = text.substringAfter('?', "")
            if (query.isBlank()) return null
            val params = HashMap<String, String>()
            query.split('&').forEach { part ->
                if (part.isBlank()) return@forEach
                val key = part.substringBefore('=')
                val value = percentDecode(part.substringAfter('=', ""))
                params[key] = value
            }
            if (params["v"] != "2") return null
            val host = params["h"].orEmpty()
            val port = params["p"]?.toIntOrNull() ?: 0
            val token = params["t"].orEmpty()
            if (host.isBlank() || port !in 1..65535 || token.isBlank()) return null
            return RelayPairUri(
                host = host,
                port = port,
                token = token,
                deviceName = params["n"].orEmpty(),
                deviceId = params["id"].orEmpty(),
            )
        }

        private fun percentDecode(value: String): String {
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%' && index + 3 <= value.length) {
                    val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (decoded != null) {
                        bytes.write(decoded)
                        index += 3
                        continue
                    }
                }
                if (char == '+') {
                    bytes.write(' '.code)
                } else {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                }
                index += 1
            }
            return bytes.toString("UTF-8")
        }
    }
}
