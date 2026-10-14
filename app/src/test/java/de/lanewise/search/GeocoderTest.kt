package de.lanewise.search

import org.junit.Assert.assertEquals
import org.junit.Test

class GeocoderTest {
    /** Owner typed "<adjective>str <no>" (e.g. "musterstädterstr 1") as start and got nothing: OSM writes "Musterstädter Straße". */
    @Test
    fun splitsAdjectiveStreetNames() {
        assertEquals("musterstädter Straße 1", Geocoder.normalize("musterstädterstr 1"))
        assertEquals("Bremer Straße 1", Geocoder.normalize("Bremerstraße 1"))
        assertEquals("Hildesheimer Straße", Geocoder.normalize("Hildesheimerstr."))
        assertEquals("lindenstr 1 musterstadt", Geocoder.normalize("lindenstr 1 musterstadt"))   // not an -er name
        assertEquals("Musterstädter Straße 1", Geocoder.normalize("Musterstädter Straße 1"))                // already split
    }
}
