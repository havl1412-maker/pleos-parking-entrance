package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.EntranceKind
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.data.RoadSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAdjustTest {

    // 남쪽에서 올라올 때 쓰는 입구(south)와 북쪽에서 내려올 때 쓰는 입구(north). 위도 0.001도 ≈ 111m
    private val south = Entrance(
        id = "south", kind = EntranceKind.IN, lat = 37.4145, lng = 127.0947,
        approachBearingDeg = 0.0, roadSide = RoadSide.RIGHT, orderFromApproach = 2,
        landmark = "", speech = "", heightLimitM = null, feeText = null, hoursText = null,
        photoFar = null, photoNear = null, confusers = emptyList(),
    )
    private val north = south.copy(id = "north", lat = 37.4147, approachBearingDeg = 180.0, roadSide = RoadSide.LEFT)
    // 내비가 보통 안내하는 건물 위치: 입구에서 북동쪽 약 60m
    private val building = Facility("f", "시험 빌딩", "", listOf(south), naviLat = 37.4150, naviLng = 127.0950)

    @Test
    fun `내비가 건물 앞으로 안내하면 입구로 바꾸자고 한다`() {
        val adj = RouteAdjust.suggest(building, 37.4150, 127.0950)!!
        assertEquals("south", adj.entrance.id)
        assertTrue("거리 ${adj.destinationToEntranceM}", adj.destinationToEntranceM in 55.0..70.0)
    }

    @Test
    fun `이미 입구로 안내하면 바꾸지 않는다`() {
        assertNull(RouteAdjust.suggest(building, 37.4145, 127.0947))
        // 내비가 입구 좌표를 길 위로 조금(약 15m) 옮겨 알려 줘도 다시 바꾸지 않는다
        assertNull(RouteAdjust.suggest(building, 37.41463, 127.0947))
    }

    @Test
    fun `목적지가 다른 건물이면 바꾸지 않는다`() {
        // 입구에서 약 220m 떨어진 옆 블록 식당
        assertNull(RouteAdjust.suggest(building, 37.4165, 127.0947))
    }

    @Test
    fun `건물 위치를 모르면 입구에서 100m 안일 때만 바꾼다`() {
        val noNavi = building.copy(naviLat = null, naviLng = null)
        assertEquals("south", RouteAdjust.suggest(noNavi, 37.4151, 127.0947)?.entrance?.id) // 약 67m
        assertNull(RouteAdjust.suggest(noNavi, 37.4156, 127.0947)) // 약 122m
    }

    @Test
    fun `입구가 둘이면 출발한 쪽에서 들어가기 좋은 입구로 바꾼다`() {
        val two = building.copy(entrances = listOf(south, north))
        assertEquals("south", RouteAdjust.suggest(two, 37.4150, 127.0950, fromLat = 37.4000, fromLng = 127.0947)?.entrance?.id)
        assertEquals("north", RouteAdjust.suggest(two, 37.4150, 127.0950, fromLat = 37.4300, fromLng = 127.0947)?.entrance?.id)
        assertEquals("south", RouteAdjust.suggest(two, 37.4150, 127.0950)?.entrance?.id) // 지금 위치를 모르면 첫 번째
    }

    @Test
    fun `나가는 전용 출구로는 바꾸지 않는다`() {
        val exitOnly = building.copy(entrances = listOf(south.copy(kind = EntranceKind.OUT)))
        assertNull(RouteAdjust.suggest(exitOnly, 37.4150, 127.0950))
    }
}
