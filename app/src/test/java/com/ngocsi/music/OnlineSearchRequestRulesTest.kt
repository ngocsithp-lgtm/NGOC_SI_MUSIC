package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchRequestRulesTest {
    @Test
    fun acceptsTheLatestSubmittedSearch() {
        assertTrue(OnlineSearchRequestRules.isCurrent(3L, 3L))
    }

    @Test
    fun rejectsAnOlderRequestWhenTheSameQueryIsSubmittedAgain() {
        assertFalse(OnlineSearchRequestRules.isCurrent(2L, 3L))
    }

    @Test
    fun rejectsARequestInvalidatedByEditingTheSearchText() {
        assertFalse(OnlineSearchRequestRules.isCurrent(3L, 4L))
    }

    @Test
    fun rejectsARequestInvalidatedWhenTheQueryIsCleared() {
        assertFalse(OnlineSearchRequestRules.isCurrent(7L, 8L))
    }
}
