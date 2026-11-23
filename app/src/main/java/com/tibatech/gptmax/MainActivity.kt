package com.tibatech.gptmax

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tibatech.gptmax.ui.navigation.GpTMaxRoot
import com.tibatech.gptmax.ui.theme.GpTMaxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GpTMaxTheme {
                GpTMaxRoot()
            }
        }
    }
}
