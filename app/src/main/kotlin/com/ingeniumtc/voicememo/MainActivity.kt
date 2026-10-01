package com.ingeniumtc.voicememo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ingeniumtc.voicememo.ui.home.HomeRoute
import com.ingeniumtc.voicememo.ui.theme.VoiceMemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceMemoTheme {
                HomeRoute()
            }
        }
    }
}
