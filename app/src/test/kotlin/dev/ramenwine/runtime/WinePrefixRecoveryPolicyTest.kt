package dev.ramenwine.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class WinePrefixRecoveryPolicyTest {
    @Test
    fun skipsOnlyACompletePrefixWithMatchingMarker() {
        assertEquals(
            WinePrefixRecoveryAction.SKIP,
            WinePrefixRecoveryPolicy.decide(
                populatedRegistryFiles = 3,
                markerExists = true,
                markerMatches = true,
                prefixHasData = false
            )
        )
    }

    @Test
    fun updatesACompletePrefixWhenRuntimeMarkerIsMissingOrStale() {
        assertEquals(
            WinePrefixRecoveryAction.UPDATE,
            WinePrefixRecoveryPolicy.decide(
                populatedRegistryFiles = 3,
                markerExists = false,
                markerMatches = false,
                prefixHasData = true
            )
        )
        assertEquals(
            WinePrefixRecoveryAction.UPDATE,
            WinePrefixRecoveryPolicy.decide(
                populatedRegistryFiles = 3,
                markerExists = true,
                markerMatches = false,
                prefixHasData = true
            )
        )
    }

    @Test
    fun updatesAnInterruptedPrefixWithoutDeletingExistingRegistryFiles() {
        for (existingRegistryFiles in 1..2) {
            assertEquals(
                WinePrefixRecoveryAction.UPDATE,
                WinePrefixRecoveryPolicy.decide(
                    populatedRegistryFiles = existingRegistryFiles,
                    markerExists = false,
                    markerMatches = false,
                    prefixHasData = true
                )
            )
        }
    }

    @Test
    fun updatesAFirstInitializationThatLeftOnlyAnUpdateTimestamp() {
        assertEquals(
            WinePrefixRecoveryAction.UPDATE,
            WinePrefixRecoveryPolicy.decide(
                populatedRegistryFiles = 0,
                markerExists = false,
                markerMatches = false,
                prefixHasData = true
            )
        )
    }

    @Test
    fun initializesOnlyAnEmptyPrefixWithoutACompletionMarker() {
        assertEquals(
            WinePrefixRecoveryAction.INITIALIZE,
            WinePrefixRecoveryPolicy.decide(
                populatedRegistryFiles = 0,
                markerExists = false,
                markerMatches = false,
                prefixHasData = false
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImpossibleRegistryFileCounts() {
        WinePrefixRecoveryPolicy.decide(
            populatedRegistryFiles = 4,
            markerExists = true,
            markerMatches = false,
            prefixHasData = true
        )
    }
}
