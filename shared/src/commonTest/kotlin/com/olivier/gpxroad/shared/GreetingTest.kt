package com.olivier.gpxroad.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greetingNamesTheSharedModule() {
        assertTrue(greeting().startsWith("GPXroad shared"))
    }
}
