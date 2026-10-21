package de.lanewise.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerHealthTest {

    @Test
    fun classifiesByAnswerAndTime() {
        assertEquals(Health.FREE, ServerHealth.classify(200, 220))
        assertEquals(Health.MODERATE, ServerHealth.classify(200, 2_400))
        assertEquals(Health.BUSY, ServerHealth.classify(200, 7_000))
        assertEquals(Health.BUSY, ServerHealth.classify(429, 100))
        assertEquals(Health.BUSY, ServerHealth.classify(504, 100))
        assertEquals(Health.DOWN, ServerHealth.classify(500, 100))
        assertEquals(Health.DOWN, ServerHealth.classify(null, 8_000))
    }

    @Test
    fun overallIsTheWorse() {
        assertEquals(Health.MODERATE, ServerHealth.worse(Health.FREE, Health.MODERATE))
        assertEquals(Health.DOWN, ServerHealth.worse(Health.DOWN, Health.FREE))
    }
}
