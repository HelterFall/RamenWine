package dev.ramenwine.display

import com.aspauldingcode.wawona.WawonaNative
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WawonaNativeContractTest {
    @Test
    fun wrapperDeclaresOnlyJniMethodsUsedFromThePinnedOfficialRelease() {
        val nativeMethods = WawonaNative::class.java.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "nativePrepareShellEnvironment",
                "nativeInit",
                "nativeIsCompositorReady",
                "nativeSetSurface",
                "nativeDestroySurface",
                "nativeResizeSurface",
                "nativeSyncOutputSize",
                "nativeSetDisplayDensity",
                "nativeShutdown",
                "nativePointerEnter",
                "nativePointerMotion",
                "nativePointerButton",
                "nativePointerLeave",
                "nativeKeyEvent"
            ),
            nativeMethods
        )
        assertEquals(
            0,
            WawonaNative::class.java.getDeclaredMethod("nativeDestroySurface").parameterCount
        )
        assertFalse("nativeApplyHostKeyLevels" in nativeMethods)
        assertFalse("nativeReloadHostKeymap" in nativeMethods)
        assertFalse("nativeTouchDown" in nativeMethods)
    }
}
