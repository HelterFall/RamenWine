package dev.ramenwine.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLocaleProfileTest {
    @Test
    fun defaultLocaleIsJapaneseAndIncludedInThePackagedProfiles() {
        assertEquals("ja_JP.UTF-8", GameLocaleProfile.DEFAULT_LOCALE)
        assertTrue(GameLocaleProfile.isSupported(GameLocaleProfile.DEFAULT_LOCALE))
    }

    @Test
    fun everySelectableLocaleHasOneUniqueIdentifier() {
        val identifiers = GameLocaleProfile.options.map { it.locale }
        assertEquals(identifiers.size, identifiers.toSet().size)
        assertTrue(identifiers.all { GameLocaleProfile.isSupported(it) })
    }

    @Test
    fun arbitraryLocalesCannotBeSelected() {
        assertFalse(GameLocaleProfile.isSupported("xx_XX.UTF-8"))
        assertFalse(GameLocaleProfile.isSupported("../../tmp"))
    }
}
