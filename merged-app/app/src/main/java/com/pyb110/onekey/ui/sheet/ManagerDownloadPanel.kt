package com.pyb110.onekey.ui.sheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ManagerDownloads
import com.pyb110.onekey.ui.components.ManagerMark
import com.pyb110.onekey.ui.components.PanelButton
import com.pyb110.onekey.ui.oneKeyColors
import com.pyb110.onekey.ui.openUrl
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ManagerDownloadPanel(
) {
    val downloadContext = LocalContext.current
    val c = oneKeyColors()
    val controller = remember { JailbreakController.get(downloadContext) }
    val pkgByKind = remember { controller.managers.associate { it.kind to it.pkg } }
    var items by remember { mutableStateOf(ManagerDownloads.all()) }
    var syncing by remember { mutableStateOf(false) }
    val panelScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (ManagerDownloads.isFresh()) {
            items = ManagerDownloads.all()
            return@LaunchedEffect
        }
        syncing = true
        withContext(Dispatchers.IO) { controller.refreshManagerCatalog(true) }
        items = ManagerDownloads.all()
        syncing = false
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (items.isEmpty()) "正在获取官方最新版本…" else "版本取自官方最新发布，点击下载打开对应发布页。",
            style = COUITheme.textStyles.body2,
            color = c.muted,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        items.forEach { item ->
            val pkg = pkgByKind[item.kind].orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { openUrl(downloadContext, item.apkUrl) }
                    .padding(vertical = 10.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ManagerMark(pkg, item.label)
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = COUITheme.textStyles.body1, color = c.ink)
                    Text(
                        item.version + " · " + item.versionCode,
                        style = COUITheme.textStyles.body2,
                        color = c.muted
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下载", style = COUITheme.textStyles.body2, color = c.primaryInk)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        COUIIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = c.primary
                    )
                }
            }
        }
        if (items.isEmpty() && !syncing) {
            Text(
                "暂未获取到官方版本信息，请检查网络后重新获取。",
                style = COUITheme.textStyles.body2,
                color = c.muted
            )
            Spacer(Modifier.height(10.dp))
            PanelButton("重新获取") {
                panelScope.launch {
                    syncing = true
                    withContext(Dispatchers.IO) { controller.refreshManagerCatalog(true) }
                    items = ManagerDownloads.all()
                    syncing = false
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "打不开下载页时，可复制发布页链接到浏览器打开。",
            style = COUITheme.textStyles.body2,
            color = c.muted
        )
    }
}
