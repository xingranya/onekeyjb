package com.pyb110.onekey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.itos.coui.theme.CouiTheme
import com.pyb110.onekey.ui.AppRoot
import com.pyb110.onekey.ui.ProvideOneKeyColors
import com.pyb110.onekey.ui.readAppearance
import com.pyb110.onekey.ui.resolveDark
import com.pyb110.onekey.ui.writeAppearance
import com.pyb110.onekey.ui.theme.readDynamicColor
import com.pyb110.onekey.ui.theme.rememberDynamicCouiScheme
import com.pyb110.onekey.ui.theme.writeDynamicColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { JailbreakController.get(applicationContext) }
            var appearance by remember { mutableStateOf(readAppearance(applicationContext)) }
            var dynamicColor by remember { mutableStateOf(readDynamicColor(applicationContext)) }
            val dark = resolveDark(appearance)
            val scheme = rememberDynamicCouiScheme(dark, dynamicColor)
            CouiTheme(darkTheme = dark, colorScheme = scheme) {
                ProvideOneKeyColors(dark) {
                    AppRoot(
                        controller = controller,
                        appearance = appearance,
                        isDark = dark,
                        dynamicColor = dynamicColor,
                        onChangeAppearance = { mode ->
                            appearance = mode
                            writeAppearance(applicationContext, mode)
                        },
                        onToggleDynamicColor = { enabled ->
                            dynamicColor = enabled
                            writeDynamicColor(applicationContext, enabled)
                        },
                    )
                }
            }
        }
    }
}
