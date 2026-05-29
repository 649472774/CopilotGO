package com.tongxie.copilotgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tongxie.copilotgo.ui.AppNavigation
import com.tongxie.copilotgo.ui.theme.CopilotGoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CopilotGoApp).container
        setContent {
            CopilotGoTheme {
                AppNavigation(container = container)
            }
        }
    }
}
