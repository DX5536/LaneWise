package de.lanewise.search

import org.junit.Assert.assertEquals
import org.junit.Test

class GeocoderTest {
    /** Owner typed "grazerstr 2" as start and got nothing: OSM writes "Grazer Straße". */
    @Test
    fun splitsAdjectiveStreetNames() {
        assertEquals("grazer Straße 2", Geocoder.normalize("grazerstr 2"))
        assertEquals("Celler Straße 10", Geocoder.normalize("Cellerstraße 10"))
        assertEquals("Hildesheimer Straße", Geocoder.normalize("Hildesheimerstr."))
        assertEquals("hauptstr 3 langenhagen", Geocoder.normalize("hauptstr 3 langenhagen"))   // not an -er name
        assertEquals("Grazer Straße 2", Geocoder.normalize("Grazer Straße 2"))                // already split
    }
}
