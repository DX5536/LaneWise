package de.lanewise.traffic

import de.lanewise.core.geo.Geo
import de.lanewise.core.geo.LatLon
import de.lanewise.core.geo.Polyline
import de.lanewise.core.model.IncidentKind
import de.lanewise.core.model.Maneuver
import de.lanewise.core.model.ManeuverType
import de.lanewise.core.model.Modifier
import de.lanewise.core.model.Route
import de.lanewise.core.model.RouteStep
import de.lanewise.lane.LaneClosure
import de.lanewise.lane.LaneDir
import de.lanewise.lane.LaneSite
import de.lanewise.lane.RoadKind
import de.lanewise.location.Fix
import de.lanewise.nav.NavigationEngine
import de.lanewise.nav.Phrases
import de.lanewise.settings.AppLanguage
import de.lanewise.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

/** DATEX II parsing of [MobilithekClient] (hand-written v2 / v3 samples following the schemas; no real feed yet). */
class MobilithekClientTest {

    private val now = Instant.parse("2026-10-17T10:00:00Z")

    // A 1 km carriageway heading north from a point in the Hannover area (made up, no real roadworks).
    private val a = LatLon(52.3000, 9.8000)
    private val b = Geo.destination(a, 0.0, 1000.0)
    private val mid1 = Geo.destination(a, 0.0, 300.0)
    private val mid2 = Geo.destination(a, 0.0, 700.0)

    private fun v2(records: String) = """<?xml version="1.0" encoding="UTF-8"?>
<d2LogicalModel xmlns="http://datex2.eu/schema/2/2_0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" modelBaseVersion="2">
 <payloadPublication xsi:type="SituationPublication" lang="de"><situation id="S1">$records</situation></payloadPublication>
</d2LogicalModel>"""

    private fun record(id: String, type: String, lanes: String, impact: String, status: String = "active",
                       start: String = "2026-10-01T06:00:00+02:00", end: String = "2026-11-30T18:00:00+01:00") = """
  <situationRecord xsi:type="$type" id="$id" version="1">
   <validity><validityStatus>$status</validityStatus>
    <validityTimeSpecification><overallStartTime>$start</overallStartTime><overallEndTime>$end</overallEndTime></validityTimeSpecification></validity>
   <impact>$impact</impact>
   <generalPublicComment><comment><values><value lang="de">Baustelle, Fahrstreifen gesperrt</value></values></comment></generalPublicComment>
   <groupOfLocations xsi:type="Linear">
    <supplementaryPositionalDescription><affectedCarriagewayAndLanes><carriageway>mainCarriageway</carriageway>$lanes</affectedCarriagewayAndLanes></supplementaryPositionalDescription>
    <locationForDisplay><latitude>${(mid1.lat + mid2.lat) / 2}</latitude><longitude>${a.lon}</longitude></locationForDisplay>
    <tpegLinearLocation>
     <from xsi:type="TpegNonJunctionPoint"><pointCoordinates><latitude>${mid1.lat}</latitude><longitude>${mid1.lon}</longitude></pointCoordinates></from>
     <to xsi:type="TpegNonJunctionPoint"><pointCoordinates><latitude>${mid2.lat}</latitude><longitude>${mid2.lon}</longitude></pointCoordinates></to>
    </tpegLinearLocation>
   </groupOfLocations>
   <roadMaintenanceType>roadworks</roadMaintenanceType>
  </situationRecord>"""

    private val twoLanes = "<originalNumberOfLanes>2</originalNumberOfLanes><numberOfLanesRestricted>1</numberOfLanesRestricted>"

    @Test
    fun parsesV2RoadworksWithAClosedLeftLane() {
        val inc = MobilithekClient.parse(v2(record("R1", "MaintenanceWorks", "<lane>leftLane</lane>", twoLanes)).byteInputStream(), now).single()
        assertEquals("datex:R1", inc.id)
        assertEquals(IncidentKind.ROADWORKS, inc.kind)
        assertEquals("Baustelle, Fahrstreifen gesperrt", inc.description)
        assertEquals(listOf(false, true), inc.laneOpen)
        assertEquals(listOf(mid1, mid2), inc.geometry)       // TPEG from → to, in driving direction
        assertTrue(inc.active)
        assertFalse(inc.blocked)
        assertFalse(inc.laneRestrictionUnknown)
    }

    /** `lane2` - counted from which side? Not reliable across feeds: restricted, lanes unknown. */
    @Test
    fun numberedLanesAreNotGuessed() {
        val inc = MobilithekClient.parse(v2(record("R2", "MaintenanceWorks", "<lane>lane2</lane>", twoLanes)).byteInputStream(), now).single()
        assertNull(inc.laneOpen)
        assertTrue(inc.laneRestrictionUnknown)
        // Only a count of restricted lanes: unknown as well.
        val counted = MobilithekClient.parse(v2(record("R3", "ConstructionWorks", "", "<numberOfLanesRestricted>1</numberOfLanesRestricted>")).byteInputStream(), now).single()
        assertTrue(counted.laneRestrictionUnknown)
    }

    @Test
    fun validityDecidesActive() {
        val xml = v2(record("S", "MaintenanceWorks", "<lane>rightLane</lane>", twoLanes, status = "suspended") +
            record("F", "MaintenanceWorks", "<lane>rightLane</lane>", twoLanes, status = "definedByValidityTimeSpec",
                start = "2026-10-20T06:00:00+02:00") +
            record("O", "MaintenanceWorks", "<lane>rightLane</lane>", twoLanes, status = "active", end = "2026-10-16T18:00:00+02:00") +
            record("T", "MaintenanceWorks", "<lane>rightLane</lane>", twoLanes, status = "definedByValidityTimeSpec"))
        val byId = MobilithekClient.parse(xml.byteInputStream(), now).associateBy { it.id.removePrefix("datex:") }
        assertEquals(mapOf("S" to false, "F" to false, "O" to false, "T" to true), byId.mapValues { it.value.active })
        assertEquals(listOf(true, false), byId.getValue("T").laneOpen)
    }

    @Test
    fun laneNamesMapOnlyWhenUnambiguous() {
        assertEquals(listOf(true, false, true), MobilithekClient.laneOpen(listOf("middleLane"), 3))
        assertNull(MobilithekClient.laneOpen(listOf("middleLane"), 4))
        assertNull(MobilithekClient.laneOpen(listOf("leftLane"), null))               // lane count unknown
        assertNull(MobilithekClient.laneOpen(listOf("hardShoulder"), 2))             // not a driving lane
        assertEquals(listOf(false, false), MobilithekClient.laneOpen(listOf("allLanesCompleteCarriageway"), 2))
        assertNull(MobilithekClient.laneOpen(listOf("leftHandTurningLane"), 3))
    }

    /** DATEX II v3 in a SOAP envelope, gzip-compressed: GML line, numbered lanes → lanes unknown; a closure is blocked. */
    @Test
    fun parsesV3InASoapEnvelopeAndGzip() {
        val xml = """<?xml version="1.0"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Body>
<d2:payload xmlns:d2="http://levelC/schema/3/d2Payload" xmlns:sit="http://datex2.eu/schema/3/situation"
  xmlns:com="http://datex2.eu/schema/3/common" xmlns:loc="http://datex2.eu/schema/3/locationReferencing"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="sit:SituationPublication">
 <sit:situation id="X">
  <sit:situationRecord xsi:type="sit:Roadworks" id="V3a" version="2">
   <sit:validity><com:validityStatus>active</com:validityStatus></sit:validity>
   <sit:impact><sit:numberOfLanesRestricted>1</sit:numberOfLanesRestricted></sit:impact>
   <sit:locationReference xsi:type="loc:SingleRoadLinearLocation">
    <loc:supplementaryPositionalDescription><loc:carriageway><loc:carriageway>mainCarriageway</loc:carriageway>
     <loc:lane><loc:laneNumber>2</loc:laneNumber></loc:lane></loc:carriageway></loc:supplementaryPositionalDescription>
    <loc:gmlLineString><loc:posList>${mid1.lat} ${mid1.lon} ${mid2.lat} ${mid2.lon}</loc:posList></loc:gmlLineString>
   </sit:locationReference>
  </sit:situationRecord>
  <sit:situationRecord xsi:type="sit:RoadOrCarriagewayOrLaneManagement" id="V3b" version="1">
   <sit:validity><com:validityStatus>active</com:validityStatus></sit:validity>
   <sit:roadOrCarriagewayOrLaneManagementType>roadClosed</sit:roadOrCarriagewayOrLaneManagementType>
   <sit:locationReference xsi:type="loc:PointLocation"><loc:pointByCoordinates><loc:pointCoordinates>
    <loc:latitude>${a.lat}</loc:latitude><loc:longitude>${a.lon}</loc:longitude></loc:pointCoordinates></loc:pointByCoordinates></sit:locationReference>
  </sit:situationRecord>
 </sit:situation>
</d2:payload></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(xml.toByteArray()) } }.toByteArray()
        val list = MobilithekClient.parse(MobilithekClient.unzip(gz), now)
        assertEquals(2, list.size)
        val works = list[0]
        assertEquals(listOf(mid1, mid2), works.geometry)
        assertTrue(works.laneRestrictionUnknown)
        assertNull(works.laneOpen)
        val closed = list[1]
        assertTrue(closed.blocked)
        assertEquals(IncidentKind.CLOSURE, closed.kind)
        assertEquals(a, closed.location)
        assertFalse(closed.laneRestrictionUnknown)
    }

    @Test
    fun noDoctypeOrExternalEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""
        val r = runCatching { MobilithekClient.parse(evil.byteInputStream(), now) }
        assertTrue(r.isFailure || r.getOrThrow().isEmpty())
    }

    // --- along the route -----------------------------------------------------------------------------------------

    private fun route(incidents: List<de.lanewise.core.model.TrafficIncident>): Route {
        val line = Polyline(listOf(a, b))
        fun step(at: Double, t: ManeuverType, p: LatLon) = RouteStep(at, 0.0, 0.0, Maneuver(t, Modifier.STRAIGHT, p, 0.0, 0.0), "Musterstraße", null, null, null)
        val site = LaneSite(900.0, listOf(LaneDir.STRAIGHT, LaneDir.STRAIGHT), null, LaneDir.STRAIGHT, false, RoadKind.TRUNK)
        return Route("osrm", line, line.length, 60.0, listOf(step(0.0, ManeuverType.DEPART, a), step(line.length, ManeuverType.ARRIVE, b)),
            listOf(site), incidents = incidents)
    }

    @Test
    fun closedLeftLaneBecomesALaneClosureOnTheRoute() {
        val inc = MobilithekClient.parse(v2(record("R1", "MaintenanceWorks", "<lane>leftLane</lane>", twoLanes)).byteInputStream(), now)
        val c = LaneClosure.along(route(inc), inc).single()
        assertEquals(300.0, c.from, 15.0)
        assertEquals(700.0, c.to, 15.0)
        assertEquals(listOf(false, true), c.open)
    }

    /** Unknown lanes restricted: "No lane data" from 400 m before and along the roadworks, no recommended lane. */
    @Test
    fun unknownLaneRestrictionMeansNoLaneAdvice() {
        val inc = MobilithekClient.parse(v2(record("R2", "MaintenanceWorks", "<lane>lane2</lane>", twoLanes)).byteInputStream(), now)
        val r = route(inc)
        assertEquals(1, LaneClosure.unknownAlong(r, inc).size)
        val engine = NavigationEngine(r)
        fun at(d: Double) = engine.update(Fix(r.geometry.pointAt(d), 20f, 0f, 5f, d.toLong() * 50), AppSettings(), Phrases(AppLanguage.ENGLISH)).first
        assertTrue(at(100.0).lanesUnknown)
        assertTrue(at(500.0).lanesUnknown)
        assertNull(at(500.0).lanes.nextAdvice)
        assertFalse(at(760.0).lanesUnknown)
    }
}
