package com.aurora.music.navigation

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph

// Android 用的是 ICU 正则：结尾的 } 不转义会直接报 PatternSyntaxException
private val ROUTE_PARAM = Regex("""\{([^}]+)\}""")

/**
 * 把 NavBackStackEntry 还原成可以直接 navigate() 的 URL。
 *
 * destination.route 是带 {arg} 的模板（例如 ebook_reader?epath={epath}），
 * 用当前 entry 的参数替换后就能原样重建返回栈。
 */
@Suppress("DEPRECATION")
fun NavBackStackEntry.filledRoute(): String? {
    val dest = destination
    if (dest is NavGraph) return null
    val pattern = dest.route ?: return null
    val args = arguments ?: Bundle.EMPTY
    return ROUTE_PARAM.replace(pattern) { m ->
        val name = m.groupValues[1]
        val value = args.get(name) ?: dest.arguments[name]?.defaultValue
        Uri.encode(value?.toString().orEmpty())
    }
}
