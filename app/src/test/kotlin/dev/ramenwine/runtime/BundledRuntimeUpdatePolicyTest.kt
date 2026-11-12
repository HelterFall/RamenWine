package dev.ramenwine.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledRuntimeUpdatePolicyTest {
    @Test
    fun installsWhenBackendIsMissing() {
        assertTrue(
            BundledRuntimeUpdatePolicy.shouldInstall(
                installedState = null,
                installedVersion = null,
                bundledVersion = "runtime-2"
            )
        )
    }

    @Test
    fun installsWhenBackendIsIncomplete() {
        assertTrue(
            BundledRuntimeUpdatePolicy.shouldInstall(
                installedState = RuntimeState.INCOMPLETE,
                installedVersion = "runtime-1",
                bundledVersion = "runtime-2"
            )
        )
    }

    @Test
    fun installsWhenPackagedVersionIsNewer() {
        assertTrue(
            BundledRuntimeUpdatePolicy.shouldInstall(
                installedState = RuntimeState.FILES_PRESENT,
                installedVersion = "runtime-1",
                bundledVersion = "runtime-2"
            )
        )
    }

    @Test
    fun skipsWhenInstalledRuntimeMatchesTheApk() {
        assertFalse(
            BundledRuntimeUpdatePolicy.shouldInstall(
                installedState = RuntimeState.FILES_PRESENT,
                installedVersion = "runtime-2",
                bundledVersion = "runtime-2"
            )
        )
    }
}
