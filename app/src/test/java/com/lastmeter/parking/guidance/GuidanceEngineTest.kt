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
    fun `지나쳐서 다음 건물 입구 쪽에서 P단이면 다른 건물 경고`() {
        val withAfter = entrance.copy(confusers = entrance.confusers + Confuser("세 번째는 다음 건물이에요", 37.41465, 127.0947, after = true))
        engine.setTarget(Facility("f3", "뒤쪽 입구 상가", "", listOf(withAfter)))
        at(37.4120); at(37.4145); at(37.41468)
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

    // ---------- 주차하고 나갈 때 ----------

    @Test
    fun `주차하고 다시 출발하면 입구 앞을 지나도 안내를 다시 하지 않는다`() {
        at(37.4120); at(37.4146)
        engine.onGear(isPark = true)
        engine.onGear(isPark = false)
        // 주차장에서 나와 입구 앞 길로 다시 나간다
        val events = listOf(37.4145, 37.4144, 37.4143, 37.4140, 37.4135, 37.4125).flatMap { at(it) }
        assertTrue("음성 $events", events.isEmpty())
        assertTrue(engine.state is GuidanceState.Idle)
    }

    @Test
    fun `주차하고 멀리 갔다가 다시 오면 다시 안내한다`() {
        at(37.4120); at(37.4146)
        engine.onGear(isPark = true)
        engine.onGear(isPark = false)
        at(37.4080) // 약 720m 남쪽으로 벗어남
        val events = listOf(37.4110, 37.4115, 37.4120, 37.4125).flatMap { at(it) }
        assertEquals(1, events.count { it is GuidanceEvent.SpeakPreview })
    }

    @Test
    fun `옆 건물일 수 있는 곳에 주차했다가 나가면 다시 안내한다`() {
        at(37.4120); at(37.4142) // 옆 건물 입구에 더 가까운 곳
        engine.onGear(isPark = true)
        assertEquals(true, (engine.state as GuidanceState.Parked).likelyOtherBuilding)
        engine.onGear(isPark = false)
        val events = at(37.4143)
        assertTrue(engine.state is GuidanceState.AtEntrance)
        assertEquals(1, events.count { it is GuidanceEvent.SpeakConfuserWarning })
    }

    @Test
    fun `입구 전 신호 대기에서 P를 넣었다 빼도 입구 안내는 그대로 한다`() {
        at(37.4120); at(37.4134) // 입구 약 120m 전에서 멈춤
        engine.onGear(isPark = true)
        assertTrue(engine.state is GuidanceState.Approaching)
        engine.onGear(isPark = false)
        val events = at(37.4140)
        assertTrue(engine.state is GuidanceState.AtEntrance)
        assertEquals(1, events.count { it is GuidanceEvent.SpeakConfuserWarning })
    }

    @Test
    fun `주차를 마친 뒤 주차장 안에서 다시 P를 넣어도 다시 묻지 않는다`() {
        at(37.4120); at(37.4146)
        engine.onGear(isPark = true)
        engine.onGear(isPark = false)
        at(37.4146)
        engine.onGear(isPark = true) // 주차장 안에서 자리를 옮겨 다시 P
        assertTrue(engine.state is GuidanceState.Idle)
    }

    // ---------- 입구가 여럿일 때 다시 고르기 ----------

    private val northEntrance = entrance.copy(id = "e2", lat = 37.4147, approachBearingDeg = 180.0, roadSide = RoadSide.LEFT, orderFromApproach = 1, confusers = emptyList())

    @Test
    fun `멀리서 정한 입구가 반대쪽이면 방향이 맞는 입구로 바꾼다`() {
        engine.setTarget(Facility("f2", "입구 둘 상가", "", listOf(entrance, northEntrance)))
        // 입구 북동쪽 옆길을 서쪽으로 달린다(두 입구 모두 90도 차이) → 먼저 적힌 e1 으로 정해진다
        var lng = 127.0985
        while (lng > 127.0948) { engine.onFix(Fix(37.4175, lng, t++ * 1000)); lng -= 0.0002 }
        assertEquals("e1", (engine.state as GuidanceState.Approaching).entrance.id)
        // 큰길로 꺾어 남쪽으로 내려온다 → e1 은 반대쪽이라 북쪽에서 들어가는 e2 로 바꾼다
        var lat = 37.4173
        var events = emptyList<GuidanceEvent>()
        while (lat > 37.4160) { events = events + engine.onFix(Fix(lat, 127.0947, t++ * 1000)); lat -= 0.00015 }
        val s = engine.state as GuidanceState.Approaching
        assertEquals("e2", s.entrance.id)
        assertEquals(false, s.otherDirection)
        // 새 입구로 미리 보기 음성을 한 번 다시 한다
        assertEquals("e2", events.filterIsInstance<GuidanceEvent.SpeakPreview>().last().entrance.id)
    }

    @Test
    fun `내비 목적지로 정한 입구를 먼저 쓴다`() {
        engine.setTarget(Facility("f2", "입구 둘 상가", "", listOf(entrance, northEntrance)))
        engine.preferredEntranceId = "e2"
        at(37.4125) // 방향을 아직 모름 → 가까운 e1 이 아니라 내비를 보낸 e2
        assertEquals("e2", (engine.state as GuidanceState.Approaching).entrance.id)
    }

    // ---------- 속도 계산 ----------

    @Test
    fun `몇 ms 차이로 겹쳐 온 위치는 속도를 망가뜨리지 않는다`() {
        listOf(37.4100, 37.4101, 37.4102, 37.4103).forEach { at(it) } // 약 11m/s
        val before = engine.speedMps!!
        engine.onFix(Fix(37.41031, 127.0947, (t - 1) * 1000 + 5)) // 5ms 뒤에 살짝 다른 위치
        assertEquals(before, engine.speedMps!!, 0.01)
    }

    @Test
    fun `위치가 한동안 끊겼다 오면 예전 속도를 쓰지 않는다`() {
        listOf(37.4080, 37.4082, 37.4084, 37.4086).forEach { at(it) } // 약 22m/s
        t += 10 // 10초 동안 위치가 안 옴
        at(37.4087)
        assertEquals(null, engine.speedMps)
        assertEquals(300.0, engine.previewDistanceM(), 0.01)
    }

    // ---------- 데이터와 다른 방향에서 올 때 ----------

    /** 북쪽에서 남쪽으로 달려 내려온다 (데이터의 진입 방향은 북쪽으로 올라가는 0도) */
    private fun southward(vararg lats: Double) = lats.flatMap { at(it) }

    @Test
    fun `입구가 하나인데 반대쪽에서 오면 순서를 말하지 않는다`() {
        val events = southward(37.4180, 37.4175, 37.4170, 37.4165) // 약 170~390m 북쪽
        val s = engine.state as GuidanceState.Approaching
        assertTrue(s.otherDirection)
        assertEquals(true, events.filterIsInstance<GuidanceEvent.SpeakPreview>().single().otherDirection)

        val near = southward(37.4155, 37.4150) // 약 55m
        val a = engine.state as GuidanceState.AtEntrance
        assertTrue(a.otherDirection)
        assertEquals(true, near.filterIsInstance<GuidanceEvent.SpeakConfuserWarning>().single().otherDirection)
    }

    @Test
    fun `정해진 방향으로 오면 그대로 안내한다`() {
        at(37.4100); at(37.4110); at(37.4120)
        assertEquals(false, (engine.state as GuidanceState.Approaching).otherDirection)
    }

    @Test
    fun `옆길에서 꺾어 들어오는 중이면 반대쪽으로 보지 않는다`() {
        // 동쪽으로 달리는 중(진입 방향과 90도): 곧 입구 길로 꺾어 들어올 수 있다
        listOf(127.0920, 127.0925, 127.0930).forEach { engine.onFix(Fix(37.4125, it, t++ * 1000)) }
        assertEquals(false, engine.isOtherDirection(entrance))
    }

    @Test
    fun `반대쪽에서 오면 헷갈리는 입구가 없어도 입구 앞에서 간판을 확인하라고 한다`() {
        engine.setTarget(Facility("f4", "옆 입구 없는 상가", "", listOf(entrance.copy(confusers = emptyList()))))
        val events = southward(37.4180, 37.4170, 37.4160, 37.4150)
        assertEquals(1, events.count { it is GuidanceEvent.SpeakConfuserWarning })
    }

    // ---------- 속도에 맞춘 미리 보기 시점 ----------

    /** 남쪽에서 입구 쪽으로 1초마다 한 번씩 위치를 넣으며 달린다. 미리 보기가 처음 뜬 거리(m)를 돌려준다 */
    private fun driveUntilPreview(startLat: Double, speedMps: Double, stopLat: Double = 37.4145): Double? {
        var lat = startLat
        while (lat < stopLat) {
            at(lat)
            val s = engine.state
            if (s is GuidanceState.Approaching) return s.distanceM
            lat += speedMps / 111_195.0
        }
        return null
    }

    @Test
    fun `시속 40km면 약 330m 전에 미리 보여 준다`() {
        val d = driveUntilPreview(startLat = 37.4095, speedMps = 40 / 3.6)!! // 약 556m 남쪽에서 출발
        assertTrue("미리 보기 시작 거리 $d", d in 321.0..334.0) // 11.1m/s × 30초 = 333m, 1초에 11.1m 씩 움직인다
    }

    @Test
    fun `시속 20km면 300m에서는 아직 안 띄우고 약 170m 전에 띄운다`() {
        val d = driveUntilPreview(startLat = 37.4095, speedMps = 20 / 3.6)!!
        assertTrue("미리 보기 시작 거리 $d", d in 160.0..168.0) // 5.6m/s × 30초 = 167m
    }

    @Test
    fun `아주 느려도 150m 전에는 띄운다`() {
        val d = driveUntilPreview(startLat = 37.4115, speedMps = 10 / 3.6)!! // 약 334m 남쪽에서 시속 10km. 30초면 83m 지만 하한 150m
        assertTrue("미리 보기 시작 거리 $d", d in 145.0..150.0)
    }

    @Test
    fun `아주 빨라도 500m보다 먼저 띄우지 않는다`() {
        val d = driveUntilPreview(startLat = 37.4085, speedMps = 100 / 3.6)!! // 시속 100km, 30초면 833m 지만 상한 500m
        assertTrue("미리 보기 시작 거리 $d", d in 470.0..500.0)
    }

    @Test
    fun `비가 올 때처럼 여유 시간을 더하면 더 일찍 띄운다`() {
        engine.extraLeadSec = 10.0
        val d = driveUntilPreview(startLat = 37.4095, speedMps = 30 / 3.6)!!
        assertTrue("미리 보기 시작 거리 $d", d in 324.0..334.0) // 8.3m/s × (30 + 10)초 = 333m
    }

    @Test
    fun `미리 보기가 뜬 뒤에는 멈춰 서도 내려가지 않는다`() {
        driveUntilPreview(startLat = 37.4095, speedMps = 60 / 3.6)
        val stopped = (engine.state as GuidanceState.Approaching).distanceM
        val lat = 37.4145 - stopped / 111_195.0
        repeat(10) { at(lat) } // 신호 대기: 속도 0 → 미리 보기 거리는 150m 로 줄지만 화면은 그대로
        assertEquals(0.0, engine.speedMps!!, 0.01)
        assertTrue(engine.state is GuidanceState.Approaching)
    }

    @Test
    fun `위치가 튀면 속도를 버리고 300m 기준으로 돌아간다`() {
        repeat(5) { at(37.4000 + it * 0.00005) } // 천천히 달리는 중 (시속 20km)
        at(37.4120) // 갑자기 1km 넘게 이동 = 위치가 튄 것. 입구까지 278m
        assertEquals(null, engine.speedMps)
        assertTrue(engine.state is GuidanceState.Approaching)
    }

    @Test
    fun `순서 표현`() {
        assertEquals("두", GuidanceEngine.ordinalKo(2))
        assertEquals("오른쪽 · 두 번째 경사로", SpeechTexts.orderLine(RoadSide.RIGHT, 2))
    }
}
