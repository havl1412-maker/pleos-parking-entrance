package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.Facility
import kotlin.math.abs

/** 내비 목적지를 이 시설의 이 입구로 바꾸자는 제안 */
data class RouteAdjustment(
    val facility: Facility,
    val entrance: Entrance,
    /** 지금 내비 목적지에서 입구까지 거리(m). 화면에 "입구는 약 60m 떨어져 있어요"로 보여 준다 */
    val destinationToEntranceM: Double,
)

/**
 * 내비가 건물 앞(지도의 건물 위치)으로 안내할 때, 목적지를 우리 데이터의 진짜 주차장 입구 좌표로 바꿀지 판단한다.
 *
 * 경로 안내는 순정 내비가 그대로 한다. 우리 앱은 목적지 좌표만 바꿔서 요청한다(NaviHelper.requestRoute).
 * 내비가 아닌 앱이 "몇 m 앞 우회전"을 하면 안 되는 단일 경로 정책에 맞는 방법이다.
 *
 * 사용자의 목적지를 바꾸는 일이라 조심한다.
 * - 목적지가 이 건물이라고 확실할 때만(입구나 건물 위치에서 100m 안) 바꾼다. 옆 건물 식당이 목적지일 수 있다.
 * - 목적지가 이미 입구 근처(25m 안)면 바꾸지 않는다. 우리가 바꾼 뒤 내비가 다시 알려 줄 때도 여기에 걸려 되풀이하지 않는다.
 * 안드로이드에 의존하지 않는 순수 코틀린이라 단위 테스트로 검증한다.
 */
object RouteAdjust {
    /** 목적지가 입구에서 이만큼 안이면 이미 입구로 안내하는 것으로 본다 */
    const val ALREADY_AT_ENTRANCE_M = 25.0

    /** 목적지가 입구나 건물 위치에서 이만큼 안이어야 "이 건물에 가는 것"으로 본다 */
    const val SAME_PLACE_M = 100.0

    fun suggest(facility: Facility, destLat: Double, destLng: Double, fromLat: Double? = null, fromLng: Double? = null): RouteAdjustment? {
        val candidates = facility.entrances.filter { it.canEnter }
        if (candidates.isEmpty()) return null
        // 목적지가 이 건물인지: 입구들과 건물 위치(내비가 보통 안내하는 지점) 중 가장 가까운 곳까지 거리
        val places = facility.entrances.map { it.lat to it.lng } +
            listOfNotNull(facility.naviLat?.let { lat -> facility.naviLng?.let { lat to it } })
        val toFacility = places.minOf { (lat, lng) -> Geo.distanceM(destLat, destLng, lat, lng) }
        if (toFacility > SAME_PLACE_M) return null
        if (candidates.any { Geo.distanceM(destLat, destLng, it.lat, it.lng) <= ALREADY_AT_ENTRANCE_M }) return null

        val entrance = pickEntrance(candidates, fromLat, fromLng) ?: return null
        return RouteAdjustment(facility, entrance, Geo.distanceM(destLat, destLng, entrance.lat, entrance.lng))
    }

    /**
     * 입구가 여러 개면, 지금 있는 곳에서 입구 쪽으로 가는 방향과 입구의 진입 방향이 가장 잘 맞는 것을 고른다.
     * (남쪽에서 출발하면 북쪽으로 올라가며 들어가는 입구) 지금 위치를 모르면 첫 번째 입구.
     */
    fun pickEntrance(candidates: List<Entrance>, fromLat: Double?, fromLng: Double?): Entrance? {
        if (candidates.size <= 1 || fromLat == null || fromLng == null) return candidates.firstOrNull()
        return candidates.minByOrNull { e ->
            abs(Geo.angleDiffDeg(Geo.bearingDeg(fromLat, fromLng, e.lat, e.lng), e.approachBearingDeg))
        }
    }
}
