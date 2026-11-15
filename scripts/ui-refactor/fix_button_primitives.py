p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/theme/CouiWidgets.kt"
s=open(p,encoding="utf-8").read()

old_start = s.index("/** 静态或可点击表面：COUI 配色 + 库按压反馈。 */")
old_end = s.index("/** 文本按钮的便捷重载：直接给文案。 */")

new = '''/**
 * 表面：COUI 的形状、配色与按压反馈。
 *
 * 形状统一用 CouiShape（COUI 的平滑圆角，与系统控件同一套曲线），
 * 不再用 Compose 的 RoundedCornerShape —— 后者圆角曲率与 COUI 不一致，放在一起会显脏。
 */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = CouiShape(14.dp, 2f),
    color: Color = CouiTheme.colorScheme.container,
    contentColor: Color = CouiTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier.clip(shape).background(color)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
    Box(
        if (onClick != null) base.couiButtonFeedback(CouiTheme.isDark, interaction, enabled)
            .clickable(interaction, null, enabled, onClick = onClick) else base,
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalCouiContentColor provides contentColor) { content() } }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = Surface(
    modifier = modifier.size(40.dp), onClick = onClick, shape = CircleShape,
    color = Color.Transparent, enabled = enabled, content = content,
)

/**
 * 主按钮：COUI 胶囊形状 + 主题强调色 + 跟手辉光。
 *
 * 辉光用库的 rememberCouiSpotlight：按下时白点在按钮表面跟手扩散，
 * 这是 ColorOS 17 原生按钮的观感，自绘无法还原。
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CouiCapsuleShape,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val (lightModifier, drawLight) = if (Build.VERSION.SDK_INT >= 33) {
        rememberCouiSpotlight(interactive = enabled, preset = SpotlightPreset.OpaqueMedium1, dark = dark)
    } else Modifier to null
    Surface(
        modifier = modifier, onClick = onClick, shape = shape,
        color = CouiTheme.colorScheme.accent, contentColor = CouiTheme.colorScheme.onAccent, enabled = enabled,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) { content() }
            if (drawLight != null) Box(Modifier.fillMaxSize().then(lightModifier).drawBehind(drawLight))
        }
    }
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Surface(
    modifier = modifier, onClick = onClick, shape = CouiCapsuleShape,
    color = Color.Transparent, contentColor = CouiTheme.colorScheme.accent, enabled = enabled,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
}

'''
s = s[:old_start] + new + s[old_end:]

s = s.replace("import com.itos.coui.theme.LocalCouiContentColor",
"""import androidx.compose.ui.draw.drawBehind
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.LocalCouiContentColor""")
s = s.replace("import androidx.compose.ui.window.Dialog\n", "")
s = s.replace("import androidx.compose.ui.window.DialogProperties\n", "")
open(p,"w",encoding="utf-8").write(s)
print("按钮层已改为 COUI 原语")
