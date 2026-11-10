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
                "nativeKeyEvent",
                "nativeCommitText",
                "nativePreeditText",
                "nativeDeleteSurroundingText",
                "nativeInjectKey",
                "nativeTextEntryWanted",
                "nativeGetTextInputContentType"
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
        assertEquals(1, WawonaNative::class.java.getDeclaredMethod(
            "nativeCommitText", String::class.java
        ).parameterCount)
        assertEquals(3, WawonaNative::class.java.getDeclaredMethod(
            "nativePreeditText", String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).parameterCount)
        assertEquals(1, WawonaNative::class.java.getDeclaredMethod(
            "nativeGetTextInputContentType", IntArray::class.java
        ).parameterCount)
    }
}
