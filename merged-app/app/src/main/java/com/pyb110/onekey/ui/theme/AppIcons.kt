package com.pyb110.onekey.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 图标集中映射：业务代码继续写 COUIIcons.Xxx，
 * 底层换成 Google 官方 Material 圆角图标（COUI alpha11 起不再自带图标包）。
 */
object COUIIcons {
    val Back: ImageVector get() = Icons.Rounded.ArrowBack
    val ChevronForward: ImageVector get() = Icons.Rounded.ChevronRight
    val Close: ImageVector get() = Icons.Rounded.Close
    val Copy: ImageVector get() = Icons.Rounded.ContentCopy
    val Delete: ImageVector get() = Icons.Rounded.Delete
    val Download: ImageVector get() = Icons.Rounded.Download
    val Help: ImageVector get() = Icons.Rounded.Help
    val Hide: ImageVector get() = Icons.Rounded.VisibilityOff
    val Home: ImageVector get() = Icons.Rounded.Home
    val Info: ImageVector get() = Icons.Rounded.Info
    val Link: ImageVector get() = Icons.Rounded.Link
    val Lock: ImageVector get() = Icons.Rounded.Lock
    val Notes: ImageVector get() = Icons.Rounded.Notes
    val Ok: ImageVector get() = Icons.Rounded.Check
    val Play: ImageVector get() = Icons.Rounded.PlayArrow
    val Recent: ImageVector get() = Icons.Rounded.History
    val Refresh: ImageVector get() = Icons.Rounded.Refresh
    val Replace: ImageVector get() = Icons.Rounded.SwapHoriz
    val Report: ImageVector get() = Icons.Rounded.Report
    val ScreenCapture: ImageVector get() = Icons.Rounded.CropFree
    val Search: ImageVector get() = Icons.Rounded.Search
    val Settings: ImageVector get() = Icons.Rounded.Settings
    val Tasks: ImageVector get() = Icons.Rounded.Checklist
    val Theme: ImageVector get() = Icons.Rounded.Palette
    val Tune: ImageVector get() = Icons.Rounded.Tune
    val Unlock: ImageVector get() = Icons.Rounded.LockOpen
    val Update: ImageVector get() = Icons.Rounded.SystemUpdate

    // 底栏专用：未选中用线稿，选中用实心。
    val HomeOutline: ImageVector get() = Icons.Outlined.Home
    val NotesOutline: ImageVector get() = Icons.Outlined.Menu
    val RecentOutline: ImageVector get() = Icons.Outlined.History
    val InfoOutline: ImageVector get() = Icons.Outlined.Info
}
