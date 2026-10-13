package com.aurora.music.ui.ebook

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.aurora.music.data.ebook.EbookFormat
import com.aurora.music.data.ebook.ebookFormatOf
import com.aurora.music.ui.ios5.Ios5Colors

/**
 * 电子书行尾的耳机图标：点进转有声书界面。
 * 转换管线只支持 EPUB，非 epub 不显示。
 */
@Composable
fun EbookExportButton(
    path: String,
    onOpenExport: (path: String) -> Unit,
) {
    if (ebookFormatOf(path) != EbookFormat.EPUB) return
    IconButton(onClick = { onOpenExport(path) }) {
        Icon(Icons.Filled.Headset, "转有声书", tint = Ios5Colors.IosBlue)
    }
}
