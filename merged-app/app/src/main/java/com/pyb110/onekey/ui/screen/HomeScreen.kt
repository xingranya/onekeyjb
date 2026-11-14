package com.pyb110.onekey.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pyb110.onekey.BuildConfig
import com.pyb110.onekey.JailbreakController
import com.pyb110.onekey.ui.components.ListCard
import com.pyb110.onekey.ui.components.ListRow
import com.pyb110.onekey.ui.components.SectionTitle
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Help
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect

@Composable
internal fun HomeScreen(
    controller: JailbreakController,
    haptic: (CouiHapticEffect) -> Unit,
    onDiagnostics: () -> Unit,
    onOpenLog: () -> Unit,
    onRunJailbreak: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenManual: () -> Unit,
    onOpenSheet: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HeroCard(controller) }
        if (controller.lastResultOk != null) {
            item {
                ResultBanner(
                    controller = controller,
                    onOpenManager = {
                        val pkg = controller.managers.firstOrNull { it.kind == controller.selectedManager }?.pkg
                        runCatching {
                            context.startActivity(
                                context.packageManager.getLaunchIntentForPackage(pkg ?: "")
                                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onOpenLog = onOpenLog,
                    onDiagnostics = onDiagnostics
                )
            }
        }
        item { ActionCard(controller, haptic, onDiagnostics, onRunJailbreak) }
        item { SectionTitle("拓展功能") }
        item {
            OptionsCard(
                controller = controller,
                haptic = haptic,
                onOpenSheet = onOpenSheet,
                onDiagnostics = onDiagnostics
            )
        }
        item { SectionTitle("更多") }
        item {
            ListCard(
                rows = listOf(
                    ListRow(
                        title = "用户手册",
                        summary = "从零开始，5 秒钟完成越狱",
                        icon = COUIIcons.Help,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); onOpenManual() }
                    ),
                    ListRow(
                        title = "关于 OneKey",
                        summary = "v" + BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE,
                        icon = COUIIcons.Info,
                        arrow = true,
                        onClick = { haptic(CouiHapticEffect.Switch); onOpenAbout() }
                    )
                )
            )
        }
    }
}
