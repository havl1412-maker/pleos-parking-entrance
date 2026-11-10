package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.Confuser
import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.EntranceKind
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.data.RoadSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuidanceEngineTest {

    // 입구는 위도 37.4145. 위도 0.001도 ≈ 111m
    private val entrance = Entrance(
        id = "e1", kind = EntranceKind.IN, lat = 37.4145, lng = 127.0947,
        approachBearingDeg = 0.0, roadSide = RoadSide.RIGHT, orderFromApproach = 2,
        landmark = "편의점 바로 다음", speech = "오른쪽 두 번째 경사로예요.",
        heightLimitM = 2.3, feeText = null, hoursText = null, photoFar = null, photoNear = null,
        confusers = listOf(Confuser("첫 번째 경사로는 옆 건물이에요", 37.4143, 127.0947)),
    )
    private val facility = Facility("f1", "시험 상가", "", listOf(entrance))
    private lateinit var engine: GuidanceEngine
    private var t = 0L

    @Before
    fun setUp() {
        engine = GuidanceEngine()
        engine.setTarget(facility)
    }

    private fun at(lat: Double) = engine.onFix(Fix(lat, 127.0947, t++ * 1000))

    @Test
    fun `멀리 있으면 대기`() {
        at(37.4100) // 약 500m 남쪽
        assertTrue(engine.state is GuidanceState.Idle)
    }

    @Test
    fun `300m 안에 들어오면 미리 보여 주고 음성은 한 번만`() {
        val first = at(37.4120) // 약 278m
        assertTrue(engine.state is GuidanceState.Approaching)
        assertEquals(1, first.count { it is GuidanceEvent.SpeakPreview })
        val second = at(37.4125)
        assertTrue(second.none { it is GuidanceEvent.SpeakPreview })
    }

    @Test
    fun `입구 앞에서는 헷갈림 경고`() {
        at(37.4120)
        val events = at(37.4140) // 약 56m
        assertTrue(engine.state is GuidanceState.AtEntrance)
        assertEquals(1, events.count { it is GuidanceEvent.SpeakConfuserWarning })
    }

    @Test
    fun `가까워졌다가 멀어지면 지나침`() {
        at(37.4120); at(37.4140); at(37.4145) // 입구 도착
        val events = at(37.4152) // 약 78m 지나감
        assertTrue(engine.state is GuidanceState.Passed)
        assertEquals(1, events.count { it is GuidanceEvent.SpeakPassed })
    }

    @Test
    fun `입구 근처에서 P단이면 주차 완료`() {
        at(37.4120); at(37.4146)
        engine.onGear(isPark = true)
        val s = engine.state
        assertTrue(s is GuidanceState.Parked)
        assertEquals(false, (s as GuidanceState.Parked).likelyOtherBuilding)
    }

    @Test
    fun `옆 건물 입구에 더 가까운 곳에서 P단이면 다른 건물 경고`() {
        at(37.4120); at(37.4142)
        engine.onGear(isPark = true)
        assertEquals(true, (engine.state as GuidanceState.Parked).likelyOtherBuilding)
    }

    @Test
    fun `입구에서 먼 곳의 P단은 무시`() {
        at(37.4120) // 약 278m
        engine.onGear(isPark = true)
        assertTrue(engine.state is GuidanceState.Approaching)
    }

    @Test
    fun `입구가 여러 개면 달리는 방향에 맞는 입구를 고른다`() {
        // 남쪽에서 올라올 때 쓰는 입구(e1)와, 북쪽에서 내려올 때 쓰는 입구(north)
        val north = entrance.copy(id = "e2", lat = 37.4147, approachBearingDeg = 180.0, roadSide = RoadSide.LEFT, orderFromApproach = 1, confusers = emptyList())
        val two = Facility("f2", "입구 둘 상가", "", listOf(entrance, north))

        engine.setTarget(two)
        at(37.4100); at(37.4110); at(37.4120) // 북쪽으로 달려 300m 안으로
        assertEquals("e1", (engine.state as GuidanceState.Approaching).entrance.id)

        engine.setTarget(two)
        at(37.4190); at(37.4180); at(37.4170) // 남쪽으로 달려 300m 안으로
        assertEquals("e2", (engine.state as GuidanceState.Approaching).entrance.id)
    }

    @Test
    fun `순서 표현`() {
        assertEquals("두", GuidanceEngine.ordinalKo(2))
        assertEquals("오른쪽 · 두 번째 경사로", SpeechTexts.orderLine(RoadSide.RIGHT, 2))
    }
}
