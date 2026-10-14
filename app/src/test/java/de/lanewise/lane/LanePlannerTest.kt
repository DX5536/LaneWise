package de.lanewise.lane

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanePlannerTest {

    private val L = LaneDir.LEFT
    private val S = LaneDir.STRAIGHT
    private val R = LaneDir.RIGHT

    private fun site(d: Double, lanes: List<Int>, turn: Int, kind: RoadKind = RoadKind.URBAN, maneuver: Boolean = turn != S) =
        LaneSite(d, lanes, null, turn, maneuver, kind)

    @Test
    fun parsesOsmTurnLanes() {
        assertEquals(listOf(L, S, S or R), LaneDir.parseTurnLanes("left|through|through;right"))
        assertEquals(listOf(LaneDir.NONE, S), LaneDir.parseTurnLanes("|through"))
        assertTrue(LaneDir.permits(LaneDir.NONE, S))
        assertTrue(LaneDir.permits(S or R, LaneDir.SLIGHT_RIGHT))
    }

    @Test
    fun lateralCoordinatesAlignTurnPockets() {
        // left pocket, two through lanes, right pocket
        val a = site(0.0, listOf(L, S, S, R), S)
        assertEquals(1, a.leftPockets())
        assertEquals(1, a.rightPockets())
        assertEquals(listOf(2, 1, 0, -1), (0 until 4).map { a.lateral(it) })
    }

    /**
     * Straight through junction A, then left at B 300 m later.
     * Classic guidance accepts both through lanes at A; LaneWise only the one that is also correct at B.
     */
    @Test
    fun avoidModePicksLaneThatAlsoWorksForNextManeuver() {
        val a = site(300.0, listOf(L, S, S, R), S)
        val b = site(600.0, listOf(L, L, S), L)

        val avoid = LanePlanner().plan(listOf(a, b), LanePlanner.Options(avoidLaneChanges = true))
        assertEquals(1, avoid[0].recommended)
        assertArrayEquals(booleanArrayOf(false, true, false, false), avoid[0].optimal)
        assertEquals(0, avoid[1].changeFromPrevious)

        val classic = LanePlanner().plan(listOf(a, b), LanePlanner.Options(avoidLaneChanges = false))
        assertEquals(1, classic.size)
        assertArrayEquals(booleanArrayOf(false, true, true, false), classic[0].optimal)
    }

    @Test
    fun motorwayKeepRightBreaksTiesWithoutAddingChanges() {
        val exitStraight = site(1000.0, listOf(S, S, S, S or LaneDir.SLIGHT_RIGHT), S, RoadKind.MOTORWAY)
        val plan = LanePlanner().plan(listOf(exitStraight), LanePlanner.Options(keepRight = true))
        assertEquals(3, plan[0].recommended)

        // Car is known to be in the middle lane: staying there beats moving right (no extra change).
        val mid = exitStraight.lateral(1)
        val stay = LanePlanner().plan(listOf(exitStraight), LanePlanner.Options(keepRight = true, currentLateral = mid))
        assertEquals(1, stay[0].recommended)
    }

    @Test
    fun countsRequiredChangesAndFlagsThem() {
        val a = site(200.0, listOf(S, S, S), S)
        val b = site(800.0, listOf(L, S, S), L) // left-only pocket on the left
        val g = LaneGuidance()
        val state = g.update(listOf(a, b), carDistance = 0.0, avoidLaneChanges = true, keepRight = false)
        val lastAdvice = state.plan.last()
        assertEquals(0, lastAdvice.recommended)
        // Exactly one lateral step overall between the start of the plan and B.
        val totalChanges = state.plan.sumOf { kotlin.math.abs(it.changeFromPrevious) }
        assertTrue(totalChanges <= 1)
    }

    @Test
    fun noChangeMeansStayInLane() {
        val a = site(300.0, listOf(S, S), S)
        val state = LaneGuidance().update(listOf(a), 0.0, avoidLaneChanges = true, keepRight = true)
        assertTrue(state.stayInLane)
        assertNull(state.pendingChange)
    }

    /** Double left-turn lanes, then a right turn shortly after: take the right one of the two left lanes. */
    @Test
    fun turnsMapIntoCorrespondingLanes() {
        val a = site(300.0, listOf(L, L, S), L, maneuver = true)
        val b = site(500.0, listOf(S, S, R), R)
        val plan = LanePlanner().plan(listOf(a, b), LanePlanner.Options())
        assertEquals(1, plan[0].recommended)
        // Both left-turn lanes avoid a later lane change (either may turn into the right lane); lane 1 is closer.
        assertArrayEquals(booleanArrayOf(true, true, false), plan[0].optimal)
        assertEquals(2, plan[1].recommended)
        // Free lane choice through the turn: turn left straight into the lane that becomes the right-turn lane.
        assertEquals(0, plan[1].changeFromPrevious)
    }

    /**
     * Owner's real case (Hannover, Celler Straße → Arndtstraße): turn right from a 3-lane approach
     * (straight+right | straight | right), then 300 m later a left turn on a 2-lane road (left | straight).
     * Turning right into the right lane and changing left later costs a lane change; LaneWise must advise
     * turning straight into the left lane instead.
     */
    @Test
    fun turnStraightIntoTheLaneNeededNext() {
        val a = site(200.0, listOf(S or R, S, R), R, maneuver = true)
        val b = site(500.0, listOf(L, S), L, maneuver = true)
        val plan = LanePlanner().plan(listOf(a, b), LanePlanner.Options())
        assertEquals(0, plan[1].recommended)
        assertEquals(0, plan[1].changeFromPrevious)
        assertEquals(b.lateral(0), plan[1].entryLateral)

        // With the strict corresponding-lane rule a change after the turn is unavoidable.
        val strict = LanePlanner().plan(listOf(a, b), LanePlanner.Options(freeTurnEntry = false))
        assertEquals(1, kotlin.math.abs(strict[1].changeFromPrevious))
    }

    @Test
    fun shortGapStillProducesAPlan() {
        val a = site(100.0, listOf(L, S, S), L, maneuver = true)
        val b = site(120.0, listOf(S, S, R), R)
        val plan = LanePlanner().plan(listOf(a, b), LanePlanner.Options())
        assertEquals(2, plan.size)
        assertEquals(0, plan[0].recommended)
    }

    @Test
    fun parsesChangeLanes() {
        assertEquals(listOf(ChangeSpan.RIGHT, ChangeSpan.LEFT or ChangeSpan.RIGHT, 0), ChangeSpan.parse("not_left|yes|no"))
        assertNull(ChangeSpan.parse("yes|yes"))          // no restriction at all
        val span = ChangeSpan(0.0, 100.0, listOf(ChangeSpan.RIGHT, ChangeSpan.LEFT))   // left lane: right only; right lane: left only
        assertTrue(span.canMoveLeft(0))                 // rightmost lane may go left
        assertTrue(!span.canMoveRight(0))               // ...but not further right
        assertTrue(span.canMoveLeft(-1))                // pockets are unrestricted
    }

    /**
     * Solid line between the two lanes from junction A up to the left turn at B (common in Hannover before
     * turn lanes): the change to the left lane must be made *before* A, where the line is still dashed.
     */
    @Test
    fun laneChangeMovesBeforeTheSolidLine() {
        val a = site(300.0, listOf(S, S), S, maneuver = false)
        val b = site(600.0, listOf(L, S), L, maneuver = true)
        val solid = ChangeSpan(300.0, 600.0, listOf(0, 0))
        val plan = LanePlanner().plan(listOf(a, b), LanePlanner.Options(currentLateral = 0, changeSpans = listOf(solid)))
        assertEquals(0, plan[0].recommended)            // already in the left lane at A
        assertEquals(0, plan[1].changeFromPrevious)     // no change across the solid line
        assertEquals(300.0, LanePlanner().blockedLength(listOf(solid), 0, 1, 300.0, 600.0), 1e-9)
        assertEquals(0.0, LanePlanner().blockedLength(listOf(solid), 0, 1, 0.0, 300.0), 1e-9)
    }

    @Test
    fun solidBoundaryForDrawing() {
        val perms = ChangeSpan.parse("not_right|not_left|yes")!!       // solid between lane 0 and 1, dashed between 1 and 2
        assertTrue(ChangeSpan.solidBoundary(perms, 1, 3))
        assertTrue(!ChangeSpan.solidBoundary(perms, 2, 3))
        // Drawn road has one lane less (pocket not open yet): aligned at the right edge.
        assertTrue(!ChangeSpan.solidBoundary(perms, 1, 2))
    }

    @Test
    fun changeInstructionIsTimedInTheDashedStretch() {
        val g = LaneGuidance()
        val solid = ChangeSpan(400.0, 600.0, listOf(0, 0))
        val windows = g.legalWindows(listOf(solid), 0, 1, 10.0, 590.0)
        assertEquals(1, windows.size)
        assertEquals(400.0, windows[0].endInclusive, 1e-9)
        val b = site(600.0, listOf(L, S), L, maneuver = true)
        val state = g.update(listOf(b), 0.0, avoidLaneChanges = true, keepRight = true, changeSpans = listOf(solid))
        // Car assumed unknown -> no change instruction; give it the right lane and re-plan.
        g.overrideCurrentLane(b, 1)
        val st = g.update(listOf(b), 0.0, avoidLaneChanges = true, keepRight = true, changeSpans = listOf(solid))
        val ch = st.pendingChange!!
        assertEquals(400.0, ch.deadline, 1e-9)
        assertTrue(ch.startAt < 400.0)
        assertNull(state.pendingChange)
    }

    /**
     * Owner's case (Celler Straße → left into Herschelstraße): 2 through lanes, a left-turn pocket opens before
     * the junction (left | through | through+right). From the left through lane, entering the pocket is not a lane
     * change, so no "move one lane left" and no change in the plan.
     */
    @Test
    fun enteringATurnPocketIsNotALaneChange() {
        val b = site(400.0, listOf(L, S, S or R), L, maneuver = true)
        // OSM: one lane in driving direction until 84 m before the junction, then three.
        val celler: (Double) -> Int? = { d -> if (d < 316.0) 1 else 3 }
        val plan = LanePlanner().plan(listOf(b), LanePlanner.Options(currentLateral = 0, laneCountAt = celler))
        assertEquals(0, plan[0].recommended)
        assertEquals(0, plan[0].changeFromPrevious)            // the lanes open beside you: no lane change
        // Car already on the 3-lane stretch in the right lane: now it is two real changes.
        val late = LanePlanner().plan(listOf(b), LanePlanner.Options(currentLateral = 0, carDistance = 330.0, laneCountAt = celler))
        assertEquals(2, late[0].changeFromPrevious)
        // Without lane-count data every crossing counts (conservative).
        assertEquals(2, LanePlanner().plan(listOf(b), LanePlanner.Options(currentLateral = 0))[0].changeFromPrevious)
        // A right-turn pocket opening: 2 lanes, then left | straight | straight | right at the junction.
        val c = site(400.0, listOf(L, S, S, R), R, maneuver = true)
        val vahr = LanePlanner().plan(listOf(c), LanePlanner.Options(currentLateral = 0, laneCountAt = { d -> if (d < 360) 2 else 4 }))
        assertEquals(0, vahr[0].changeFromPrevious)
    }

    /**
     * Owner's video (2026-10-14, Celler Straße → Herschelstraße): once the car had passed the point where the road
     * widens, the opening lanes suddenly counted as a change — a late "move one lane left" 50 m before the turn, and
     * the highlight stayed in the middle straight-only lane until then. The free move is judged from where the lane
     * was last known (the previous junction), not from the car.
     */
    @Test
    fun laneThatOpenedBehindTheCarIsStillFree() {
        val a = site(200.0, listOf(S), S)
        val b = site(400.0, listOf(L, S, S or R), L, maneuver = true)
        val celler: (Double) -> Int? = { d -> if (d < 316.0) 1 else 3 }
        val g = LaneGuidance()
        for (car in listOf(150.0, 250.0, 300.0, 330.0, 360.0, 390.0)) {
            val st = g.update(listOf(a, b), car, avoidLaneChanges = true, keepRight = true, laneCountAt = celler)
            val adv = st.plan.last()
            assertEquals("car $car", 0, adv.recommended)
            assertEquals("car $car", 0, adv.changeFromPrevious)
            assertEquals("car $car", null, st.pendingChange)
        }
    }

    /**
     * Owner's screenshot (Schiffgraben → left into Berliner Allee): OSRM flagged the 2nd left lane, the straight lane
     * and the straight+right lane as valid for the left turn, and the lane bar recommended the straight lane. The
     * arrows win at a turn: both left lanes are usable, nothing else.
     */
    @Test
    fun wrongProviderFlagsAtATurnAreIgnored() {
        val site = LaneSite(772.0, listOf(L, L, S, S or R), listOf(false, true, true, true), L, true, RoadKind.URBAN)
        assertArrayEquals(booleanArrayOf(true, true, false, false), site.allowed())
        val plan = LanePlanner().plan(listOf(site), LanePlanner.Options())
        assertTrue(plan[0].recommended in 0..1)
        // Flags that only narrow down the arrows (e.g. "use the 2nd left lane") are still trusted.
        val narrow = LaneSite(772.0, listOf(L, L, S, S or R), listOf(false, true, false, false), L, true, RoadKind.URBAN)
        assertArrayEquals(booleanArrayOf(false, true, false, false), narrow.allowed())
    }

    /** TomTom lane separators → lane permissions; OSM `change:lanes` wins where both exist. */
    @Test
    fun tomTomSeparatorsBecomeSolidLines() {
        val L1 = ChangeSpan.LEFT; val R1 = ChangeSpan.RIGHT
        // left | through | right with a dashed line between 1-2 and a solid one between 2-3.
        val perms = ChangeSpan.fromTomTomSeparators(listOf("SINGLE_SOLID", "LONG_DASHED", "SINGLE_SOLID", "SINGLE_SOLID"), 3)!!
        assertEquals(listOf(L1 or R1, L1, R1), perms)
        // All dashed: no restriction at all.
        assertNull(ChangeSpan.fromTomTomSeparators(listOf("SINGLE_SOLID", "SHORT_DASHED", "SINGLE_SOLID"), 2))
        // Mixed line (dashed on the left side, solid on the right side): only the left lane may cross it.
        assertEquals(listOf(L1 or R1, R1), ChangeSpan.fromTomTomSeparators(listOf("SINGLE_SOLID", "DASHED_SOLID", "SINGLE_SOLID"), 2))
        val osm = listOf(ChangeSpan(100.0, 200.0, listOf(R1, L1)))
        val tt = listOf(ChangeSpan(150.0, 180.0, perms), ChangeSpan(400.0, 430.0, perms))
        assertEquals(listOf(100.0, 400.0), ChangeSpan.merge(osm, tt).map { it.from })
    }

    /** A real change is timed on the road (fixed start), not from the moving car. */
    @Test
    fun changeStartDoesNotSlideWithTheCar() {
        val a = site(100.0, listOf(S, S), S)
        val b = site(400.0, listOf(L, S), L, maneuver = true)
        val g = LaneGuidance()
        g.update(listOf(a, b), 120.0, true, true)
        g.overrideCurrentLane(b, 1) // driver says: I'm in the right (straight) lane
        val starts = listOf(130.0, 300.0, 330.0, 370.0).map { car ->
            g.update(listOf(a, b), car, true, true).pendingChange!!.startAt
        }
        assertEquals(1, starts.distinct().size)
    }

    /** Lanes OSRM put on the intersection 50 m before a left turn whose junction has none: they are the turn lanes. */
    @Test
    fun laneDataBeforeAnUnmappedTurnIsUsedForTheTurn() {
        val before = LaneSite(2530.0, listOf(L, S or R), listOf(false, true), S, false, RoadKind.URBAN)
        val fixed = attachTurnsToLaneSites(listOf(before), listOf(2580.0 to L)).single()
        assertTrue(fixed.isManeuver)
        assertEquals(L, fixed.turn)
        assertEquals(0, LanePlanner().plan(listOf(fixed), LanePlanner.Options())[0].recommended)
        // Far away (> 80 m) or with a site at the turn itself: unchanged.
        assertEquals(S, attachTurnsToLaneSites(listOf(before), listOf(2700.0 to L)).single().turn)
    }

    /**
     * Owner's video (2026-10-14, Schulenburger Landstraße → right into Beneckeallee): `left | through | through;right`,
     * then two lanes (OSM) for 350 m, then `through | right`. The right lane is the old through;right lane, not a pocket:
     * keep right, no lane change. Before the fix the plan kept the car in the left lane and asked to move right 160 m
     * before the turn.
     */
    @Test
    fun turnOnlyLaneThatRunsTheWholeWayIsNotAPocket() {
        val a = site(300.0, listOf(L, S, S or R), S)
        val b = site(650.0, listOf(S, R), R, maneuver = true)
        val counts: (Double) -> Int? = { d -> if (d < 300.0) 3 else 2 }
        val sites = markFullLengthLanes(listOf(a, b), counts)
        assertTrue(sites[1].fullLength)
        assertEquals(0, sites[1].lateral(1))
        val g = LaneGuidance()
        for (car in listOf(250.0, 320.0, 450.0, 550.0, 620.0)) {
            val st = g.update(sites, car, avoidLaneChanges = true, keepRight = true, laneCountAt = counts)
            val adv = st.plan.last()
            assertEquals("car $car", 1, adv.recommended)
            assertEquals("car $car", 0, adv.changeFromPrevious)
            assertNull("car $car", st.pendingChange)
        }
        // A real pocket (three lanes at the junction after two through lanes) stays a pocket, even when OSM counts it
        // along the whole way.
        val c = site(650.0, listOf(S, S, R), R, maneuver = true)
        assertTrue(!markFullLengthLanes(listOf(a, c)) { 3 }[1].fullLength)
        // Unknown lane counts: unchanged.
        assertTrue(!markFullLengthLanes(listOf(a, b)) { null }[1].fullLength)
    }
}
