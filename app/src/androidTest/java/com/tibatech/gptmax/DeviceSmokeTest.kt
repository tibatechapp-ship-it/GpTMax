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
 * On-device gate for the CI emulator job: the app installs, the native ABI
 * target matches, and the guardrails behave identically on device.
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
    fun primaryAbiIsArm64() {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        assertEquals("arm64-v8a", abi)
    }
}
