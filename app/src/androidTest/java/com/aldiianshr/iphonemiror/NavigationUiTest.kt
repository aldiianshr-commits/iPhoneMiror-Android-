package com.aldiianshr.iphonemiror

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.aldiianshr.iphonemiror.ui.AppScreen
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI test: every sidebar destination opens and shows real (not placeholder) content. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun allPagesAreReachable() {
        compose.onNodeWithTag("dashboard_hero").assertIsDisplayed()
        val expectations = mapOf(
            AppScreen.CONNECT to "Wi-Fi · AirPlay",
            AppScreen.DEVICES to "Perangkat aktif",
            AppScreen.MIRRORING to "Nyalakan penerima dari Dashboard.",
            AppScreen.TOUCH to "Touch Control: Unsupported",
            AppScreen.HISTORY to "Riwayat kosong",
            AppScreen.ACCOUNT to "Integrasi Apple Account",
            AppScreen.SETTINGS to "Uji mandiri penerima",
            AppScreen.ABOUT to "Keterbatasan",
            AppScreen.DASHBOARD to "Perangkat terakhir",
        )
        for ((screen, text) in expectations) {
            compose.onNodeWithTag("nav_${screen.name}").performClick()
            compose.waitForIdle()
            assertTrue("$screen should show '$text'", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun touchControlReportsUnsupportedWithoutInputPath() {
        // No Bluetooth HID host and no WebDriverAgent on the emulator: must say so, never pretend.
        compose.onNodeWithTag("nav_TOUCH").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Touch Control: Unsupported").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("AirPlay hanya mengirim gambar", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun mirroringScreenNeverShowsVideoWithoutStream() {
        compose.onNodeWithTag("nav_MIRRORING").performClick()
        compose.waitForIdle()
        // With no iPhone stream the waiting overlay must cover the video surface.
        compose.onNodeWithText("Nyalakan penerima dari Dashboard.").assertIsDisplayed()
    }
}
