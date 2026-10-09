package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchRequestRulesTest {
    @Test
    fun acceptsOnlyCurrentRequestWithSameNonBlankQuery() {
        assertTrue(OnlineSearchRequestRules.isCurrent(3L, 3L, "jazz", "jazz"))
    }

    @Test
    fun rejectsCancelledOlderRequestEvenWhenTheQueryWasRepeated() {
        assertFalse(OnlineSearchRequestRules.isCurrent(2L, 3L, "jazz", "jazz"))
    }

    @Test
    fun rejectsResultsForChangedQuery() {
        assertFalse(OnlineSearchRequestRules.isCurrent(3L, 3L, "jazz", "rock"))
    }

    @Test
    fun rejectsBlankQuery() {
        assertFalse(OnlineSearchRequestRules.isCurrent(3L, 3L, " ", " "))
    }
}
