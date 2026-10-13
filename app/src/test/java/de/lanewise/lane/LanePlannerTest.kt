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
        assertArrayEquals(booleanArrayOf(false, true, false), plan[0].optimal)
        assertEquals(2, plan[1].recommended)
        assertEquals(-1, plan[1].changeFromPrevious)
    }

    @Test
    fun shortGapStillProducesAPlan() {
        val a = site(100.0, listOf(L, S, S), L, maneuver = true)
        val b = site(120.0, listOf(S, S, R), R)
        val plan = LanePlanner().plan(listOf(a, b), LanePlanner.Options())
        assertEquals(2, plan.size)
        assertEquals(0, plan[0].recommended)
    }
}
