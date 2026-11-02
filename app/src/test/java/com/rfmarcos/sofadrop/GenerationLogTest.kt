package com.rfmarcos.sofadrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class GenerationLogTest {

    @Test
    fun describeLeavesOutQueries() {
        val error = IOException("HTTP 500 for https://api.mdblist.com/lists/user/top?apikey=secret123&limit=100")
        val text = GenerationLog.describe(error)
        assertFalse(text, text.contains("secret123"))
        assertEquals("IOException: HTTP 500 for https://api.mdblist.com/lists/user/top?…", text)
    }

    @Test
    fun describeWithoutMessage() {
        assertEquals("OutOfMemoryError", GenerationLog.describe(OutOfMemoryError()))
    }
}
