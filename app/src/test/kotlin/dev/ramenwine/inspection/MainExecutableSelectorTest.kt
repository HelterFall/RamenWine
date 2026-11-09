package dev.ramenwine.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainExecutableSelectorTest {
    @Test
    fun selectsExecutableNamedAfterGameFolder() {
        val selected = MainExecutableSelector.select(
            listOf(
                MainExecutableSelector.Hint(
                    "MoonStory/MoonStory.exe",
                    PeArchitecture.X86_64,
                    true
                ),
                MainExecutableSelector.Hint(
                    "MoonStory/setup.exe",
                    PeArchitecture.X86_64,
                    true
                )
            ),
            "MoonStory"
        )

        assertEquals("MoonStory/MoonStory.exe", selected)
    }

    @Test
    fun recognizesCommonVisualNovelEngineExecutables() {
        val selected = MainExecutableSelector.select(
            listOf(
                MainExecutableSelector.Hint(
                    "Novel/RPG_RT.exe",
                    PeArchitecture.X86,
                    true
                ),
                MainExecutableSelector.Hint(
                    "Novel/unins000.exe",
                    PeArchitecture.X86,
                    true
                )
            ),
            "Novel"
        )

        assertEquals("Novel/RPG_RT.exe", selected)
    }

    @Test
    fun refusesToGuessBetweenUnfamiliarExecutables() {
        val selected = MainExecutableSelector.select(
            listOf(
                MainExecutableSelector.Hint("Novel/alpha.exe", PeArchitecture.X86_64, true),
                MainExecutableSelector.Hint("Novel/beta.exe", PeArchitecture.X86_64, true)
            ),
            "Novel"
        )

        assertNull(selected)
    }

    @Test
    fun ignoresInstallersAndUnsupportedArchitectures() {
        val selected = MainExecutableSelector.select(
            listOf(
                MainExecutableSelector.Hint("Novel/setup.exe", PeArchitecture.X86_64, true),
                MainExecutableSelector.Hint("Novel/MoonStory.exe", PeArchitecture.ARM64, true),
                MainExecutableSelector.Hint("Novel/Game.exe", PeArchitecture.X86_64, false)
            ),
            "Novel"
        )

        assertNull(selected)
    }
}
