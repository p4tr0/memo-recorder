package com.ingeniumtc.voicememo.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.ingeniumtc.voicememo.ui.theme.VoiceMemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class HomeScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLight() = capture(darkTheme = false, name = "home_empty_light")

    @Test
    fun emptyDark() = capture(darkTheme = true, name = "home_empty_dark")

    private fun capture(darkTheme: Boolean, name: String) {
        composeRule.setContent {
            VoiceMemoTheme(darkTheme = darkTheme, dynamicColor = false) { HomeScreen() }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
