package de.lanewise.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SunTest {
    private val hannover = LatLon(52.3759, 9.7320)
    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun elevationMatchesKnownValues() {
        // Solar noon at the summer solstice: 90 − 52.38 + 23.44 ≈ 61.1°.
        assertEquals(61.1, Sun.elevation(hannover, t("2026-06-21T11:21:00Z")), 0.7)
        // Winter solstice noon: 90 − 52.38 − 23.44 ≈ 14.2°.
        assertEquals(14.2, Sun.elevation(hannover, t("2026-12-21T11:22:00Z")), 0.7)
    }

    @Test
    fun darkAtNightLightByDay() {
        assertFalse(Sun.isDark(hannover, t("2026-10-14T10:00:00Z")))
        assertTrue(Sun.isDark(hannover, t("2026-10-14T21:00:00Z")))   // 23:00 local
        assertTrue(Sun.isDark(hannover, t("2026-12-21T16:30:00Z")))   // sunset ≈ 15:05 UTC
        assertFalse(Sun.isDark(hannover, t("2026-06-21T19:30:00Z")))  // 21:30 local, sun just setting
        assertTrue(Sun.isDark(hannover, t("2026-06-21T20:30:00Z")))
    }
}
