package de.lanewise.core.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverpassRemarkTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test
    fun timedOutQueryIsAnError() {
        val r = parse("""{"version":0.6,"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 91 seconds."}""")
        assertEquals("runtime error: Query timed out in \"query\" at line 1 after 91 seconds.", Overpass.runtimeError(r))
    }

    @Test
    fun normalAnswerIsNot() {
        assertNull(Overpass.runtimeError(parse("""{"version":0.6,"elements":[{"type":"way","id":1}]}""")))
        assertNull(Overpass.runtimeError(parse("""{"elements":[],"remark":"runtime remark: Timeout is 90 seconds."}""")))
    }
}
