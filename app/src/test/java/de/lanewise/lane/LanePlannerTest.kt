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
        val starts = listOf(130.0, 150.0, 170.0).map { car ->
            g.update(listOf(a, b), car, true, true).pendingChange!!.startAt
        }
        assertEquals(1, starts.distinct().size)
        // Driven through (60 m per lane in town): the change counts as made - no "move left" any more, and the plan
        // starts from the left lane (it no longer flips to "change after the junction" near it; audit, Bornumer Straße).
        val done = g.update(listOf(a, b), starts[0] + 70.0, true, true)
        assertNull(done.pendingChange)
        assertEquals(b.lateral(0), done.assumedLateral)
        for (car in listOf(330.0, 370.0, 395.0)) assertEquals(0, g.update(listOf(a, b), car, true, true).plan.last().recommended)
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

    /**
     * Owner's demo (A 7 towards Großburgwedel): after the motorway merge the strip stayed in the left lane for 6 km.
     * Rechtsfahrgebot: back to the right lane after the junction, change for the exit only in the lead distance.
     * In town (free lane choice) the car may stay left.
     */
    @Test
    fun keepRightOnTheMotorwayBetweenJunctions() {
        fun run(kind: RoadKind): List<LaneState> {
            val merge = LaneSite(100.0, listOf(S, S), listOf(true, false), S, false, kind)   // merge: left lane only
            val exit = LaneSite(4000.0, listOf(S, S, R), null, R, true, kind)
            val g = LaneGuidance()
            return listOf(50.0, 150.0, 1000.0, 2000.0, 3500.0).map { car -> g.update(listOf(merge, exit), car, true, true) }
        }
        val motorway = run(RoadKind.MOTORWAY)
        assertEquals(1, motorway[0].plan.first().site.lateral(motorway[0].plan.first().recommended))
        // After the merge: right lane expected (lateral 0), the strip glides there from 450 m (first motorway sample + 300 m).
        assertEquals(0, motorway[2].assumedLateral)
        assertEquals(450.0, motorway[2].keepRightFrom!!, 1e-6)
        assertEquals(1, motorway[2].keepRightFromLateral)
        // The exit (right pocket) is one change from the right lane, advised in the lead distance only.
        val change = motorway[3].pendingChange!!
        assertEquals(-1, change.lanes)
        assertTrue(change.startAt >= 4000.0 - 600.0 - 20)
        // In town nothing moves: the car stays in the lane the junction left it in.
        val town = run(RoadKind.URBAN)
        assertEquals(1, town[2].assumedLateral)
        assertNull(town[2].keepRightFrom)
    }

    /**
     * Owner's test drive (A 37 → A 7, Kreuz Hannover-Buchholz): the route is already on a barrier-separated one-lane
     * branch (OSM lanes=1) but OSRM shows `through | through | exit` — "move right", then "move left" again. Trimmed to
     * the one real lane. An exit lane that opens before the junction (OSM 3 then 4 lanes) keeps the full picture.
     */
    @Test
    fun motorwayLanePictureTrimmedToTheCarriageway() {
        val exit = LaneSite(1000.0, listOf(0, 0, LaneDir.SLIGHT_RIGHT), listOf(false, false, true), LaneDir.SLIGHT_RIGHT, true, RoadKind.MOTORWAY)
        val trimmed = trimToRoadLanes(listOf(exit), { 1 }, { true }).single()
        assertEquals(listOf(LaneDir.SLIGHT_RIGHT), trimmed.lanes)
        assertEquals(listOf(true), trimmed.providerValid)
        // A real exit pocket: OSM 3 lanes, 4 for the last 150 m — unchanged.
        val a7 = LaneSite(5000.0, listOf(0, 0, 0, LaneDir.SLIGHT_RIGHT), listOf(false, false, false, true), LaneDir.SLIGHT_RIGHT, true, RoadKind.MOTORWAY)
        assertEquals(a7, trimToRoadLanes(listOf(a7), { d -> if (d < 4850) 3 else 4 }, { true }).single())
        // Not on a motorway / expressway, or lane counts unknown: unchanged.
        assertEquals(exit, trimToRoadLanes(listOf(exit), { 1 }, { false }).single())
        assertEquals(exit, trimToRoadLanes(listOf(exit), { null }, { true }).single())
    }

    /** Audit route 16: Blumenauer Straße → left into Fössestraße, a junction with two nodes 40 m apart. */
    @Test
    fun doubleNodeJunctionUsesTheTurnLanesAtTheFirstNode() {
        val first = LaneSite(12259.0, listOf(L, S, R), listOf(false, true, true), S, false, RoadKind.URBAN)
        val turn = LaneSite(12299.0, listOf(L, L, S), listOf(true, true, false), L, true, RoadKind.URBAN)
        val again = LaneSite(12320.0, listOf(L, L, S), listOf(true, true, false), S, false, RoadKind.URBAN)
        val merged = mergeDoubleNodeJunctions(listOf(first, turn, again))
        assertEquals(2, merged.size)                                  // the repeat after the turn is dropped
        assertTrue(merged[0].allowed()[0])                            // left lane usable at the first node
        val plan = LanePlanner().plan(merged, LanePlanner.Options(currentLateral = 1, carDistance = 12000.0))
        assertEquals(0, plan[0].recommended)                          // already in the left lane at the first node
        assertEquals(0, plan[1].changeFromPrevious)                   // no change between the two nodes
    }

    /** Audit leftovers: no drift into a widening lane and back; a "straight" with only left-arrow lanes is a left turn. */
    @Test
    fun noFreeDriftAndHiddenTurns() {
        // One lane widening to [L | S | S+R], then [L | S+R] again: stay in the right lane, no 0 → 1 → 0.
        val a = site(500.0, listOf(L, S, S or R), S)
        val b = site(1100.0, listOf(L, S or R), S)
        val counts: (Double) -> Int? = { d -> if (d < 420) 1 else if (d < 520) 3 else 2 }
        val plan = LanePlanner().plan(listOf(a, b), LanePlanner.Options(currentLateral = 0, laneCountAt = counts))
        assertEquals(0, a.lateral(plan[0].recommended))
        assertEquals(0, b.lateral(plan[1].recommended))
        // Aegidientorplatz: straight on the two left-arrow lanes = a left turn.
        val hidden = LaneSite(9949.0, listOf(L, L, LaneDir.SLIGHT_RIGHT, LaneDir.SLIGHT_RIGHT), listOf(true, true, false, false), S, false, RoadKind.URBAN)
        val t = turnsHiddenAsStraight(listOf(hidden)).single()
        assertTrue(t.isManeuver)
        assertEquals(L, t.turn)
        // A normal straight site (through lanes usable) stays as it is.
        val normal = LaneSite(100.0, listOf(L, S, S), listOf(false, true, true), S, false, RoadKind.URBAN)
        assertEquals(normal, turnsHiddenAsStraight(listOf(normal)).single())
    }

    /** A2 test run: right lane closed by roadworks until 800 m - no move into it before that (exit lane after it). */
    @Test
    fun closedLaneIsNotEnteredBeforeTheClosureEnds() {
        val span = LaneClosure(200.0, 800.0, listOf(true, true, false)).toChangeSpan()!!
        // Lateral 0 = the closed right lane, 1 = the open lane next to it: it may not move right, the closed one may leave.
        org.junit.Assert.assertFalse(span.canMoveRight(1))
        org.junit.Assert.assertTrue(span.canMoveLeft(0))
        org.junit.Assert.assertTrue(span.canMoveRight(2))
        val windows = LaneGuidance().legalWindows(listOf(span), 1, 0, 0.0, 1000.0)
        org.junit.Assert.assertEquals(listOf(0.0..200.0, 800.0..1000.0), windows)
        org.junit.Assert.assertNull(LaneClosure(0.0, 10.0, listOf(true, true)).toChangeSpan())
    }

    /** Keep right on the Autobahn stops left of a lane closed by roadworks. */
    @Test
    fun keepRightStopsAtAClosedLane() {
        val s = RoadKind.MOTORWAY
        val st = LaneDir.STRAIGHT
        val a = LaneSite(100.0, listOf(st, st, st), null, st, false, s)
        val b = LaneSite(4000.0, listOf(st, st, st), null, st, false, s)
        // Right lane closed from 500 m to 3500 m.
        val c = LaneClosure(500.0, 3500.0, listOf(true, true, false))
        val closure = c.toChangeSpan()!!
        val sites = listOf(a, c.site(s)!!, b)
        val g = LaneGuidance()
        // As in NavigationEngine: the closure is a checkpoint (open lanes only) and a no-change-into span.
        g.update(sites, 50.0, true, true, listOf(closure), roadKindAt = { s })
        g.update(sites, 300.0, true, true, listOf(closure), roadKindAt = { s })
        val st2 = g.update(sites, 1500.0, true, true, listOf(closure), roadKindAt = { s })
        org.junit.Assert.assertTrue("lateral ${st2.assumedLateral}", (st2.assumedLateral ?: 0) >= 1)
    }

    /** Aegidientorplatz: a road whose lanes all turn left has no pockets - each left-turn lane stays its own lane. */
    @Test
    fun allTurnLanesAreNoPockets() {
        val armLanes = LaneDir.parseTurnLanes("reverse;left|left")
        val arm = LaneSite(696.0, armLanes, null, LaneDir.SLIGHT_LEFT, true, RoadKind.URBAN)
        org.junit.Assert.assertEquals(0, arm.leftPockets())
        org.junit.Assert.assertEquals(listOf(1, 0), listOf(arm.lateral(0), arm.lateral(1)))
        val approach = LaneSite(637.0, LaneDir.parseTurnLanes("reverse;left|left|right|right|right"), null, LaneDir.LEFT, true, RoadKind.URBAN)
        val allowed = approach.allowed()
        // The 1st left-turn lane enters the arm's left lane, the 2nd its right lane - not both the same one.
        val p = LanePlanner()
        org.junit.Assert.assertEquals(1, p.entryLateral(approach, 0, allowed, arm))
        org.junit.Assert.assertEquals(0, p.entryLateral(approach, 1, allowed, arm))
    }

    /**
     * Owner's demo (Hildesheimer Straße): a U-turn at a junction with `left | through | through;right`, OSRM's lanes
     * sitting on the U-turn's own node as "straight" with the through lane flagged valid - the lane bar recommended the
     * straight lane. A U-turn (Wenden) is made from the left-turn lane.
     */
    @Test
    fun uTurnUsesTheLeftTurnLaneNotTheThroughLane() {
        val osrm = LaneSite(950.0, listOf(L, S, S or R), listOf(false, true, false), S, false, RoadKind.URBAN)
        val site = attachTurnsToLaneSites(listOf(osrm), listOf(956.0 to LaneDir.UTURN)).single()
        assertEquals(LaneDir.UTURN, site.turn)
        assertTrue(site.isManeuver)
        assertArrayEquals(booleanArrayOf(true, false, false), site.allowed())
        assertEquals(0, LanePlanner().plan(listOf(site), LanePlanner.Options())[0].recommended)
        // No lane can make the U-turn (no left-turn lane): no lane is usable for it.
        assertTrue(listOf(S, S or R).none { LaneDir.permits(it, LaneDir.UTURN) })
    }

    /**
     * Owner's drawing (2026-10-17): two through lanes, widening to `left | through | through | right` before a left
     * turn 500 m ahead, car in the right lane. In town the move into the left lane is advised right away (free lane
     * choice, § 7 (3) StVO) - not 150 m before the junction - and only across the lane that is already there; the
     * left-turn pocket is entered as it opens. Mirrored for a right turn. On the Autobahn it stays late (keep right).
     */
    @Test
    fun inTownGetIntoTheTurnLaneEarly() {
        val counts: (Double) -> Int? = { d -> if (d < 560) 2 else 4 }
        fun change(turn: Int, startLane: Int, kind: RoadKind): LaneChangeInstruction? {
            val b = LaneSite(600.0, listOf(L, S, S, R), null, turn, true, kind)
            val g = LaneGuidance()
            g.overrideCurrentLane(b, startLane)
            return g.update(listOf(b), 100.0, avoidLaneChanges = true, keepRight = true, laneCountAt = counts).pendingChange
        }
        val left = change(L, 2, RoadKind.URBAN)!!                // right through lane → left
        assertTrue("starts at ${left.startAt}", left.startAt <= 140.0)
        assertEquals(1, left.lanes)                             // one lane now; the pocket opens later
        assertEquals(0, left.targetLane)
        val right = change(R, 1, RoadKind.URBAN)!!               // left through lane → right
        assertTrue("starts at ${right.startAt}", right.startAt <= 140.0)
        assertEquals(-1, right.lanes)
        // Autobahn, exit 1.5 km ahead, two lanes to cross: not right away, but in the lead distance (850 m).
        val exit = LaneSite(1600.0, listOf(S, S, R), null, R, true, RoadKind.MOTORWAY)
        val gm = LaneGuidance(); gm.overrideCurrentLane(exit, 0)
        val mw = gm.update(listOf(exit), 100.0, avoidLaneChanges = true, keepRight = true).pendingChange!!
        assertEquals(1600.0 - 10 - 850, mw.startAt, 1.0)
        // The strip moves too: 150 m after the car it is already in the left through lane.
        val b = LaneSite(600.0, listOf(L, S, S, R), null, L, true, RoadKind.URBAN)
        val g = LaneGuidance(); g.overrideCurrentLane(b, 2)
        val st = g.update(listOf(b), 100.0, avoidLaneChanges = true, keepRight = true, laneCountAt = counts)
        val r = de.lanewise.core.model.Route("t", de.lanewise.core.geo.Polyline(listOf(de.lanewise.core.geo.LatLon(52.38, 9.74),
            de.lanewise.core.geo.Geo.destination(de.lanewise.core.geo.LatLon(52.38, 9.74), 0.0, 1000.0))), 1000.0, 60.0, emptyList(), listOf(b),
            laneCounts = listOf(de.lanewise.core.model.LaneCountSpan(0.0, 560.0, 2), de.lanewise.core.model.LaneCountSpan(560.0, 1000.0, 4)))
        val lat = LaneRibbonBuilder.stripLateralAt(r, st, 100.0, 250.0)!!
        assertTrue("strip at lateral $lat 150 m on", lat >= 0.9)
    }

    /**
     * Lane audit (Langenforther Straße, on-ramp approach): `left | through | through`, then `through | slight_right`
     * three times within 120 m, two lanes all the way. The first repeat counted as "full length", the next two had a
     * right pocket: the same lane got two lateral numbers, the plan asked "move right" and the strip went 0 → 1 → 0.
     */
    @Test
    fun repeatedLanePictureIsReadTheSameWay() {
        val sr = LaneDir.SLIGHT_RIGHT
        val sites = markFullLengthLanes(listOf(
            site(3555.0, listOf(L, S, S), S),
            site(3598.0, listOf(S, sr), S),
            site(3655.0, listOf(S, sr), S),
            site(3721.0, listOf(S, sr), S),
        )) { d -> if (d < 3480) 2 else if (d < 3560) 3 else 2 }
        assertEquals(listOf(false, true, true, true), sites.map { it.fullLength })
        assertEquals(1, sites.drop(1).map { it.lateral(0) }.distinct().single())
        val plan = LanePlanner().plan(sites, LanePlanner.Options(currentLateral = 1))
        assertTrue(plan.all { it.changeFromPrevious == 0 })
    }

    /**
     * Lane audit (Nenndorfer Landstraße → left into Gehrdener Damm): OSRM had lanes only at the turn's node; OSM
     * `turn:lanes` added `left | through` 43 m before it, after the junction clean-up had run - the plan took the
     * straight lane there and asked "move left" in the last 43 m. Sites added from OSM are merged like OSRM's.
     */
    @Test
    fun siteAddedFromOsmBeforeATurnIsPartOfTheJunction() {
        val a = de.lanewise.core.geo.LatLon(52.33, 9.63)
        val line = de.lanewise.core.geo.Polyline(listOf(a, de.lanewise.core.geo.Geo.destination(a, 270.0, 1000.0)))
        val turn = LaneSite(897.0, listOf(L, S), listOf(true, true), L, true, RoadKind.URBAN)
        val r = de.lanewise.core.model.Route("osrm", line, 1000.0, 60.0, emptyList(), listOf(turn))
            .withOsmTurnLanes(listOf(854.0 to "left|through"))
        assertEquals(2, r.laneSites.size)
        assertTrue(r.laneSites.first().allowed()[0])            // the left lane is usable at the first node
        val plan = LanePlanner().plan(r.laneSites, LanePlanner.Options(currentLateral = 0))
        assertEquals(0, plan[0].recommended)
        assertEquals(0, plan[1].changeFromPrevious)
    }

    /**
     * Lane audit (Bückeburger Allee): an on-ramp lane joins on the right (`none | none | merge_to_left`). It is not a
     * through lane: the car's right lane keeps lateral 0, and the ending lane is never recommended.
     */
    @Test
    fun accelerationLaneIsNotAThroughLane() {
        val n = LaneDir.NONE
        val join = site(33458.0, listOf(n, n, LaneDir.MERGE_TO_LEFT), S, RoadKind.TRUNK)
        assertEquals(1, join.rightPockets())
        assertEquals(listOf(1, 0, -1), (0..2).map { join.lateral(it) })
        assertArrayEquals(booleanArrayOf(true, true, false), join.allowed())
        val plan = LanePlanner().plan(listOf(join), LanePlanner.Options(currentLateral = 0))
        assertEquals(1, plan[0].recommended)
    }

    /** Lane audit (Bückeburger Allee roundabout): two nodes 2 m apart with as many lanes are read the same way. */
    @Test
    fun twoNodesOfOneJunctionAreReadTheSameWay() {
        val sites = markFullLengthLanes(listOf(
            site(5015.0, listOf(L, S, S), S),
            site(5599.0, listOf(S or R, R), S),
            site(5663.0, listOf(S or R, R), S),
            site(5665.0, listOf(L or S or R, R), S),
        )) { 2 }
        assertEquals(listOf(false, true, true, true), sites.map { it.fullLength })
        val plan = LanePlanner().plan(sites, LanePlanner.Options(currentLateral = 1))
        assertTrue(plan.all { it.changeFromPrevious == 0 })
    }

    /** Lane audit (Friedrich-Ebert-Straße roundabout): only the entry's lanes are planned, not the circulating nodes. */
    @Test
    fun lanesInsideARoundaboutAreDropped() {
        val sites = listOf(site(6884.0, listOf(L, S, S, S or R), S), site(6982.0, listOf(S, S or R, R), S),
            site(6990.0, listOf(L, S or R), S), site(7047.0, listOf(S, S or R), LaneDir.SLIGHT_LEFT),
            site(7099.0, listOf(L or S, S), S), site(7300.0, listOf(L, S), S))
        val kept = dropRoundaboutInterior(sites, listOf(6982.0..7099.0)).map { it.distanceAlong }
        assertEquals(listOf(6884.0, 6982.0, 7300.0), kept)
    }

    /** Lane audit (Blumenauer Straße → left into Fössestraße): an equal change goes into the longer stretch. */
    @Test
    fun changeGoesIntoTheLongerStretch() {
        val a = LaneSite(12166.0, listOf(L, S or R), null, S, false, RoadKind.URBAN)
        val b = LaneSite(12259.0, listOf(L, S, R), listOf(true, true, true), S, false, RoadKind.URBAN)
        val c = LaneSite(12299.0, listOf(L, L, S), listOf(true, true, false), L, true, RoadKind.URBAN)
        val plan = LanePlanner().plan(listOf(a, b, c), LanePlanner.Options(currentLateral = 0, carDistance = 12000.0))
        assertEquals(0, plan[1].recommended)        // already in the left lane at the first node
        assertEquals(0, plan[2].changeFromPrevious)  // nothing left for the 40 m between the nodes
    }

    /**
     * AMAP survey + DOP20 (Hollerithallee): `turn:lanes:forward=none|left|through|right` with
     * `access:lanes:forward=psv|yes|yes|yes` - the unmarked left lane is a bus lane and is never recommended.
     */
    @Test
    fun busLaneIsNeverRecommended() {
        val tags = mapOf("highway" to "tertiary", "lanes" to "5", "lanes:forward" to "4", "turn:lanes:forward" to "none|left|through|right",
            "access:lanes:forward" to "psv|yes|yes|yes", "bus:lanes:forward" to "designated|yes|yes|yes")
        val car = de.lanewise.osm.OsmTags.carLanes(tags, forward = true, oneway = false)!!
        assertEquals(listOf(false, true, true, true), car)
        assertEquals(listOf(false, true), de.lanewise.osm.OsmTags.carLanes(mapOf("oneway" to "yes", "bus:lanes" to "designated|"), true, true))
        val site = LaneSite(500.0, listOf(LaneDir.NONE, L, S, R), null, S, false, RoadKind.URBAN, carLanes = car)
        assertArrayEquals(booleanArrayOf(false, false, true, false), site.allowed())
        assertEquals(2, LanePlanner().plan(listOf(site), LanePlanner.Options(currentLateral = 3)).single().recommended)
    }

    /**
     * Merging-lanes check: three lanes, the right one ends 200 m after junction A (OSM `merge_to_left`, no junction),
     * then a right turn at B on the two lanes left. The lane frame is anchored on the rightmost lane, so before the fix
     * the planner sent a car in the middle lane over into the ending right lane ("the lane for the turn") and back.
     */
    @Test
    fun rightLaneThatEndsOnTheWayIsNotTheLaneForTheNextTurn() {
        val a = site(100.0, listOf(S, S, S), S)
        val b = site(500.0, listOf(S, S or R), R, maneuver = true)
        val counts: (Double) -> Int? = { d -> if (d < 300) 3 else 2 }
        val sites = markEndedLanes(listOf(a, b), listOf(0.0 to 0, 300.0 to 1), counts)
        assertEquals(1, sites[1].endedRight)
        assertEquals(300.0, sites[1].endedRightAt!!, 0.1)
        // In the middle lane: it becomes the right lane - stay, no change.
        val plan = LanePlanner().plan(sites, LanePlanner.Options(currentLateral = 1, laneCountAt = counts))
        assertEquals(1, plan[0].recommended)
        assertEquals(0, plan[1].changeFromPrevious)
        assertEquals(1, plan[1].recommended)
        val g = LaneGuidance()
        for (car in listOf(50.0, 150.0, 280.0, 400.0)) {
            assertNull("car $car", g.update(sites, car, avoidLaneChanges = true, keepRight = true, laneCountAt = counts).pendingChange)
        }
    }

    /** In the lane that ends (right edge, `merge_to_left`): one change to the left, due before the lane ends, not at B. */
    @Test
    fun leaveTheEndingLaneBeforeItEnds() {
        // At A only the right lane is usable (e.g. the lane a slip road joins on).
        val a = LaneSite(100.0, listOf(S, S, S), listOf(false, false, true), S, false, RoadKind.URBAN)
        val b = site(800.0, listOf(S, S), S)
        val counts: (Double) -> Int? = { d -> if (d < 400) 3 else 2 }
        val sites = markEndedLanes(listOf(a, b), listOf(400.0 to 1), counts)
        val g = LaneGuidance()
        g.update(sites, 50.0, avoidLaneChanges = true, keepRight = true, laneCountAt = counts)
        val st = g.update(sites, 120.0, avoidLaneChanges = true, keepRight = true, laneCountAt = counts)
        // The car came through A in the right lane: it ends at 400 m.
        val change = st.pendingChange
        assertTrue("a change is advised", change != null)
        assertEquals(1, change!!.lanes)
        assertTrue("due before the lane ends: ${change.deadline}", change.deadline <= 400.0)
        assertTrue("starts before it ends: ${change.startAt}", change.startAt < 390.0)
        // Without a tagged side the old behaviour stays (the left lane is assumed to end): no change advised.
        val g2 = LaneGuidance()
        g2.update(listOf(a, b), 50.0, avoidLaneChanges = true, keepRight = true, laneCountAt = counts)
        assertNull(g2.update(listOf(a, b), 120.0, avoidLaneChanges = true, keepRight = true, laneCountAt = counts).pendingChange)
    }

    @Test
    fun endedOnRightReadsMergeToLeft() {
        val t = mapOf("oneway" to "yes", "lanes" to "3", "turn:lanes" to "through|through|merge_to_left")
        assertEquals(1, de.lanewise.osm.OsmTags.endedOnRight(t, true, true, 1))
        assertEquals(0, de.lanewise.osm.OsmTags.endedOnRight(mapOf("turn:lanes" to "merge_to_right|through|through"), true, true, 1))
        assertEquals(1, de.lanewise.osm.OsmTags.endedOnRight(mapOf("turn:lanes:backward" to "through|merge_to_left"), false, false, 2))
        assertEquals(0, de.lanewise.osm.OsmTags.endedOnRight(t, true, true, 0))
    }

    /** Audit regression (B 65): an on-ramp lane joins after A and ends (`merge_to_left`): no frame shift, no zig-zag. */
    @Test
    fun accelerationLaneThatJoinsAndEndsDoesNotShiftTheLanes() {
        val a = site(100.0, listOf(S, S), S)
        val b = site(900.0, listOf(S, S), S)
        val counts: (Double) -> Int? = { d -> if (d < 300) 2 else if (d < 500) 3 else 2 }
        val sites = markEndedLanes(listOf(a, b), listOf(300.0 to 0, 500.0 to 1), counts)
        assertEquals(0, sites[1].endedRight)
        val g = LaneGuidance()
        for (car in listOf(50.0, 200.0, 450.0, 700.0)) {
            assertNull("car $car", g.update(sites, car, avoidLaneChanges = true, keepRight = true, laneCountAt = counts).pendingChange)
        }
    }
}
