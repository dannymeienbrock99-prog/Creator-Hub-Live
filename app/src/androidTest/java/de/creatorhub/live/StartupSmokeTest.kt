package de.creatorhub.live

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun mainScreenStartsAndStaysOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity wurde unerwartet beendet", activity.isFinishing)
            }
            Thread.sleep(2_000)
            scenario.onActivity { activity ->
                assertFalse("MainActivity ist nach dem Kamerastart beendet", activity.isFinishing)
            }
        }
    }

    @Test
    fun settingsScreenStartsAndStaysOpen() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("SettingsActivity wurde unerwartet beendet", activity.isFinishing)
            }
        }
    }

    @Test
    fun chatScreenStartsAndStaysOpen() {
        ActivityScenario.launch(ChatActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("ChatActivity wurde unerwartet beendet", activity.isFinishing)
            }
            Thread.sleep(1_000)
        }
    }
}
