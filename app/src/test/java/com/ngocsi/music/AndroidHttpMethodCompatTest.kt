package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHttpMethodCompatTest {
    @Test
    fun patchUsesPostAndGoogleMethodOverrideHeader() {
        assertEquals(
            HttpMethodPlan(requestMethod = "POST", overrideHeader = "PATCH"),
            AndroidHttpMethodCompat.plan("PATCH")
        )
    }

    @Test
    fun patchMethodNameIsNormalizedCaseInsensitively() {
        assertEquals(
            HttpMethodPlan(requestMethod = "POST", overrideHeader = "PATCH"),
            AndroidHttpMethodCompat.plan(" patch ")
        )
    }

    @Test
    fun commonMethodsKeepTheirOriginalVerb() {
        listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS").forEach { method ->
            val plan = AndroidHttpMethodCompat.plan(method)
            assertEquals(method, plan.requestMethod)
            assertNull(plan.overrideHeader)
        }
    }
}
