package de.lanewise.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmJoinTest {

    private val rate = 1000   // 1 sample per ms keeps the numbers readable

    private fun clip(silenceBefore: Int, sound: Int, silenceAfter: Int) =
        ShortArray(silenceBefore) + ShortArray(sound) { 8000 } + ShortArray(silenceAfter)

    @Test
    fun trimsSilenceButKeepsAShortEdge() {
        val t = PcmJoin.trim(clip(300, 500, 400), channels = 1, rate = rate, keepMs = 20)
        assertEquals(540, t.size)
        assertEquals(0, t.first().toInt())
        assertEquals(8000, t[20].toInt())
    }

    @Test
    fun joinsWithAShortGap() {
        // "Turn right onto" + "Celler Straße": 1 s and 0.9 s of sound with 0.3-0.4 s silence each → no long pause.
        val a = PcmJoin.trim(clip(100, 1000, 300), 1, rate, keepMs = 0)
        val b = PcmJoin.trim(clip(400, 900, 100), 1, rate, keepMs = 0)
        val j = PcmJoin.join(listOf(a, b), 1, rate, gapMs = 60)
        assertEquals(1000 + 60 + 900, j.size)
    }

    @Test
    fun stereoAndSilentClips() {
        val st = ShortArray(20) + shortArrayOf(9000, 9000, 9000, 9000) + ShortArray(20)
        assertEquals(4, PcmJoin.trim(st, channels = 2, rate = rate, keepMs = 0).size)
        assertEquals(0, PcmJoin.trim(ShortArray(100), 1, rate).size)
    }
}
