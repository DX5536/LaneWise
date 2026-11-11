package de.lanewise.routing

import de.lanewise.lane.LaneDir
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** OSRM lane data without the network: lane order (left → right), arrows, `valid` flags and where the site lies. */
class OsrmParseTest {

    private val answer = """
    {"code":"Ok","routes":[{"distance":684.0,"duration":60.0,
      "geometry":{"type":"LineString","coordinates":[[9.0,52.0],[9.005,52.0],[9.01,52.0]]},
      "legs":[{"annotation":{"nodes":[1,2,3]},"steps":[
        {"distance":684.0,"duration":60.0,"name":"Musterstraße",
         "maneuver":{"type":"depart","location":[9.0,52.0],"bearing_before":0,"bearing_after":90},
         "intersections":[
           {"location":[9.0,52.0],"bearings":[90],"out":0},
           {"location":[9.005,52.0],"bearings":[0,90,180,270],"in":3,"out":1,"entry":[true,true,true,false],
            "lanes":[{"indications":["left"],"valid":false},{"indications":["straight"],"valid":true},
                     {"indications":["straight","right"],"valid":true}]}]},
        {"distance":0.0,"duration":0.0,"name":"Musterstraße",
         "maneuver":{"type":"arrive","location":[9.01,52.0],"bearing_before":90,"bearing_after":0},
         "intersections":[{"location":[9.01,52.0],"bearings":[270],"in":0}]}]}]}]}
    """

    @Test
    fun lanesComeLeftToRightWithTheirValidFlags() {
        val route = OsrmRoutingProvider().parse(Json.parseToJsonElement(answer)).single()
        val site = route.laneSites.single()
        assertEquals(listOf(LaneDir.LEFT, LaneDir.STRAIGHT, LaneDir.STRAIGHT or LaneDir.RIGHT), site.lanes)
        assertEquals(listOf(false, true, true), site.providerValid)
        assertEquals(LaneDir.STRAIGHT, site.turn)
        assertEquals(route.geometry.length / 2, site.distanceAlong, 5.0)
        assertEquals(listOf(1L, 2L, 3L), route.osmNodes)
    }
}
