package com.tibatech.gptmax

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device gate for the CI emulator job: the app installs, the packaged
 * native ABI matches the device, and the guardrails behave identically on
 * device.
 *
 * Review #4: the expected ABI set is injected by Gradle (resValue
 * `expected_abis`) — "arm64-v8a" for phone builds and
 * "arm64-v8a,x86_64" for the CI emulator job — so the same test gates
 * both targets without hardcoding the primary ABI.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    @Test
    fun targetPackageMatchesApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.startsWith("com.tibatech.gptmax"))
    }

    @Test
    fun guardrailsRunOnDevice() {
        assertTrue(SensitivePaths.isSensitive(".aws/credentials"))
        assertFalse(SensitivePaths.isSensitive("src/main/kotlin/App.kt"))
        assertTrue(PathGuard.isAllowed("src/App.kt"))
        assertFalse(PathGuard.isAllowed("../escape.kt"))
    }

    @Test
    fun deviceAbiIsPackaged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packaged = context.getString(R.string.expected_abis)
            .split(',')
            .map { it.trim() }
        val primary = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        assertTrue(
            "device primary ABI $primary is not among the packaged ABIs $packaged",
            primary != null && primary in packaged,
        )
    }
}
