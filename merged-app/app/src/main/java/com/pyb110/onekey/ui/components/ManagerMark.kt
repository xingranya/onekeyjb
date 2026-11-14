package com.pyb110.onekey.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.ui.oneKeyColors
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 读取已安装管理器的真实图标；读不到时退回字母标记。 */
@Composable
internal fun ManagerMark(pkg: String, label: String, size: Int = 36) {
    val context = LocalContext.current
    val c = oneKeyColors()
    val bitmap = remember(pkg) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.primarySoft),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size((size - 8).dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Text(
                label.take(1).uppercase(),
                style = COUITheme.textStyles.body2,
                color = c.primaryInk
            )
        }
    }
}
