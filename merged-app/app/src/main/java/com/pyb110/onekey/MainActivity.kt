package com.pyb110.onekey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.pyb110.onekey.ui.AppRoot
import com.pyb110.onekey.ui.ProvideOneKeyColors
import com.pyb110.onekey.ui.readAppearance
import com.pyb110.onekey.ui.resolveDark
import com.pyb110.onekey.ui.writeAppearance
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { JailbreakController.get(applicationContext) }
            var appearance by remember { mutableStateOf(readAppearance(applicationContext)) }
            val dark = resolveDark(appearance)
            val themeController = remember(dark) {
                ThemeController(
                    ColorSchemeMode.MonetSystem,
                    keyColor = if (dark) Color(0xFFE4ADBD) else Color(0xFF9B4A5A),
                    isDark = dark
                )
            }
            COUITheme(controller = themeController) {
                ProvideOneKeyColors(dark) {
                    AppRoot(
                        controller = controller,
                        appearance = appearance,
                        isDark = dark,
                        onChangeAppearance = { mode ->
                            appearance = mode
                            writeAppearance(applicationContext, mode)
                        }
                    )
                }
            }
        }
    }
}
