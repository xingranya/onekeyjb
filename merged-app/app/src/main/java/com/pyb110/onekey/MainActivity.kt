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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { JailbreakController.get(applicationContext) }
            var appearance by remember { mutableStateOf(readAppearance(applicationContext)) }
            val dark = resolveDark(appearance)
            CouiTheme(darkTheme = dark) {
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
