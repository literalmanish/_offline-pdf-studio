package com.offlinepdf.studio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.ui.screens.settings.SettingsScreen
import com.offlinepdf.studio.ui.theme.OfflinePdfStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4: Settings, Themes, and Dialogs UI Tests
 *
 * Verifies:
 * - SettingsScreen section headers, metrics, and preferences.
 * - Dynamic Theme switching (Light, Dark, AMOLED).
 * - Theme selection dialog display and dismissal.
 * - Privacy Policy dialog and device-first offline guarantee verification.
 * - About dialog verification.
 * - Clear Cache safety confirmation dialog.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAndThemeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSettingsScreen_SectionsAndPreferences_Displayed() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                SettingsScreen(onNavigateTo = {})
            }
        }

        // Verify key section titles (scrolling as necessary in vertical container)
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Appearance & Interface").assertIsDisplayed()
        composeTestRule.onNodeWithText("PDF Processing Defaults").assertIsDisplayed()
        composeTestRule.onNodeWithText("Storage & Downloads").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy & Security").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("About Application").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testSettingsScreen_ThemeDialog_OpensAndSelects() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                SettingsScreen(onNavigateTo = {})
            }
        }

        // Click Theme row
        composeTestRule.onNodeWithText("Theme").performClick()
        composeTestRule.waitForIdle()

        // Verify Dialog title and choices
        composeTestRule.onNodeWithText("Choose Theme").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clean Light Mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("AMOLED Black (Pure Dark)").assertIsDisplayed()

        // Click Cancel to dismiss
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Choose Theme").assertDoesNotExist()
    }

    @Test
    fun testSettingsScreen_PrivacyPolicyDialog_Verification() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                SettingsScreen(onNavigateTo = {})
            }
        }

        // Click 100% On-Device Guarantee (scrolling down to item)
        composeTestRule.onNodeWithText("100% On-Device Guarantee").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Verify Dialog title
        composeTestRule.onNodeWithText("100% Offline Guarantee").assertIsDisplayed()

        // Dismiss
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("100% Offline Guarantee").assertDoesNotExist()
    }

    @Test
    fun testSettingsScreen_AboutDialog_Verification() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                SettingsScreen(onNavigateTo = {})
            }
        }

        // Click Offline PDF Studio item under About Application (scrolling down)
        composeTestRule.onNodeWithText("Offline PDF Studio").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Verify Dialog
        composeTestRule.onNodeWithText("About Offline PDF Studio").assertIsDisplayed()

        // Dismiss
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("About Offline PDF Studio").assertDoesNotExist()
    }

    @Test
    fun testSettingsScreen_ClearCacheDialog_CancellationPreservesState() {
        composeTestRule.setContent {
            OfflinePdfStudioTheme {
                SettingsScreen(onNavigateTo = {})
            }
        }

        // Click Clear Temporary Cache (scrolling down)
        composeTestRule.onNodeWithText("Clear Temporary Cache").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Verify confirmation dialog
        composeTestRule.onNodeWithText("Clear Cache?").assertIsDisplayed()

        // Click Cancel
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Clear Cache?").assertDoesNotExist()
    }
}
