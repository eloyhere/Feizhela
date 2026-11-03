package com.feizhela.headband

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Product navigation and explicit demo boundary; supports phone and two-pane record layouts. */
class ComposeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun enterExplicitDemoMode() {
        composeRule.onNodeWithTag("demo_welcome_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("enter_demo").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun tonightIsTheStartDestinationAndShowsFourDestinations() {
        composeRule.onNodeWithTag("tonight_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tonight").assertIsSelected()
        listOf("nav_tonight", "nav_records", "nav_diary", "nav_device").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("open_settings").assertIsDisplayed()
        composeRule.onNodeWithText("演示数据").assertIsDisplayed()
    }

    @Test
    fun navigationOpensEveryTopLevelScreenWithCorrectSelection() {
        listOf("records", "diary", "device", "tonight").forEach { route ->
            composeRule.onNodeWithTag("nav_$route").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("${route}_screen").assertIsDisplayed()
            composeRule.onNodeWithTag("nav_$route").assertIsSelected()
        }
    }

    @Test
    fun recordsListOpensDetailAndReturnsToListOnPhone() {
        composeRule.onNodeWithTag("nav_records").performClick()
        composeRule.onNodeWithTag("record_item_demo-night-8h").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("session_detail_screen").assertIsDisplayed()
        // Wide windows select detail in the second pane without pushing a new destination.
        if (composeRule.onAllNodesWithTag("navigate_back").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("navigate_back").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("records_screen").assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("nav_records").assertIsSelected()
        }
    }

    @Test
    fun settingsKeepsOriginSelectedAndToolbarBackReturns() {
        composeRule.onNodeWithTag("nav_device").performClick()
        composeRule.onNodeWithTag("open_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_device").assertIsSelected()
        composeRule.onNodeWithTag("open_settings").assertDoesNotExist()
        composeRule.onNodeWithTag("theme_mode_dark").performScrollTo().performClick()
        composeRule.onNodeWithTag("theme_mode_dark").assertIsSelected()
        composeRule.onNodeWithTag("navigate_back").performClick()
        composeRule.onNodeWithTag("device_screen").assertIsDisplayed()
    }

    @Test
    fun systemBackReturnsFromSettingsToTonight() {
        composeRule.onNodeWithTag("open_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("tonight_screen").assertIsDisplayed()
    }

    @Test
    fun diaryRejectsInvalidTimeAndOverflowingAwakeMinutes() {
        composeRule.onNodeWithTag("nav_diary").performClick()
        composeRule.onNodeWithTag("diary_sleep_start").performScrollTo().performTextReplacement("25:70")
        composeRule.onNodeWithTag("save_diary").performScrollTo().performClick()
        composeRule.onNodeWithText("请输入 0:00–23:59 之间的时间（入睡时间）").assertIsDisplayed()
        composeRule.onNodeWithTag("diary_sleep_start").performScrollTo().performTextReplacement("23:00")
        composeRule.onNodeWithTag("diary_awake_minutes").performScrollTo().performTextReplacement("999999999999999999")
        composeRule.onNodeWithTag("save_diary").performScrollTo().performClick()
        composeRule.onNodeWithText("清醒分钟需填写有效的非负整数").assertIsDisplayed()
    }
}
