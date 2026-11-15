p="/Users/xingranya/Downloads/onekeyjb-private/merged-app/app/src/main/java/com/pyb110/onekey/ui/components/Basic.kt"
s=open(p,encoding="utf-8").read()

# PanelButton 改用 COUI 胶囊 + 辉光
start=s.index("internal fun PanelButton(")
end=s.index("@Composable\ninternal fun ManualSection")
new='''internal fun PanelButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val dark = CouiTheme.isDark
    val accent = CouiTheme.colorScheme.accent
    val (lightModifier, drawLight) = if (android.os.Build.VERSION.SDK_INT >= 33 && enabled) {
        rememberCouiSpotlight(
            interactive = true,
            preset = if (primary) SpotlightPreset.OpaqueMedium1 else SpotlightPreset.OpaqueMedium2,
            dark = dark,
        )
    } else Modifier to null
    val interaction = remember { MutableInteractionSource() }
    val shape = CouiCapsuleShape
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(if (primary && enabled) accent else CouiTheme.colorScheme.container)
            .couiButtonFeedback(dark, interaction, enabled)
            .clickable(interaction, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text,
                    style = COUITheme.textStyles.body1,
                    color = when {
                        primary && enabled -> CouiTheme.colorScheme.onAccent
                        enabled -> accent
                        else -> CouiTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (drawLight != null) Box(Modifier.fillMaxSize().then(lightModifier).drawBehind(drawLight))
        }
    }
}

'''
s = s[:start] + new + s[end:]

# DialogActions 也换成 COUI 形状与辉光
dstart=s.index("@Composable\ninternal fun DialogActions(")
dend=s.index("@Composable\ninternal fun Avatar(")
dnew='''@Composable
internal fun DialogActions(vararg actions: DialogAction) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            DialogActionButton(action, Modifier.weight(1f))
        }
    }
}

/** 单个弹窗动作按钮：COUI 胶囊、强调色与跟手辉光。 */
@Composable
private fun DialogActionButton(action: DialogAction, modifier: Modifier = Modifier) {
    val dark = CouiTheme.isDark
    val accent = CouiTheme.colorScheme.accent
    val (lightModifier, drawLight) = if (android.os.Build.VERSION.SDK_INT >= 33 && action.enabled) {
        rememberCouiSpotlight(
            interactive = true,
            preset = if (action.primary) SpotlightPreset.OpaqueMedium1 else SpotlightPreset.OpaqueMedium2,
            dark = dark,
        )
    } else Modifier to null
    val interaction = remember { MutableInteractionSource() }
    val shape = CouiCapsuleShape
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(if (action.primary) accent else CouiTheme.colorScheme.container)
            .couiButtonFeedback(dark, interaction, action.enabled)
            .clickable(interaction, null, action.enabled, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    action.icon, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = if (action.primary) CouiTheme.colorScheme.onAccent
                           else if (action.enabled) accent
                           else CouiTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    action.label, style = COUITheme.textStyles.body2,
                    color = if (action.primary) CouiTheme.colorScheme.onAccent
                            else if (action.enabled) accent
                            else CouiTheme.colorScheme.onSurfaceVariant
                )
            }
            if (drawLight != null) Box(Modifier.fillMaxSize().then(lightModifier).drawBehind(drawLight))
        }
    }
}

'''
s = s[:dstart] + dnew + s[dend:]

s = s.replace("import com.pyb110.onekey.ui.theme.*",
"""import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import com.pyb110.onekey.ui.theme.*""")
open(p,"w",encoding="utf-8").write(s)
print("Basic.kt 按钮已改 COUI 原语")
