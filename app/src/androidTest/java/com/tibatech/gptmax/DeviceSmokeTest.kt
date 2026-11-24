package com.tibatech.gptmax

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tibatech.gptmax.core.guardrails.PathGuard
import com.tibatech.gptmax.core.guardrails.SensitivePaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device gate for the CI emulator job (fix #4): the app installs, the
 * native engine is packaged for the runtime's primary ABI, and the
 * guardrails behave identically on a real Android runtime.
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

    /**
     * Real devices must be arm64-v8a; hosted CI emulators report x86_64 and
     * the CI device gate builds both ABIs via
     * -Pgptmax.abiFilters=arm64-v8a,x86_64 (see app/build.gradle.kts).
     */
    @Test
    fun primaryAbiIsBuilt() {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        assertTrue("unexpected primary ABI: $abi", abi == "arm64-v8a" || abi == "x86_64")
    }

    /** The packaged JNI engine (native libs) must match the runtime ABI. */
    @Test
    fun nativeEngineIsPackagedForThisRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        val expectedDir = when (abi) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x86_64"
            else -> return // other ABIs are not built by this project
        }
        val libDir = context.applicationInfo.nativeLibraryDir
        assertTrue(
            "native engine missing for $abi (libDir=$libDir)",
            libDir.endsWith(expectedDir) && java.io.File(libDir).isDirectory,
        )
    }
}
