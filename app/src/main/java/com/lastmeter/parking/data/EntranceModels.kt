package com.lastmeter.parking.data

/** 주차장이 있는 건물(시설) 하나. 입구가 여러 개일 수 있다. */
data class Facility(
    val id: String,
    val name: String,
    val address: String,
    val entrances: List<Entrance>,
    /**
     * 내비에서 이 건물을 목적지로 고르면 보통 안내가 끝나는 지점(지도의 건물 위치). 선택.
     * 입구와 떨어져 있으면 앱이 내비 목적지를 입구로 바꾸자고 한다(guidance/RouteAdjust.kt). 없으면 입구 좌표로만 판단한다
     */
    val naviLat: Double? = null,
    val naviLng: Double? = null,
)

enum class EntranceKind { IN, OUT, BOTH }

enum class RoadSide { LEFT, RIGHT }

/** 차량 입구 하나. 좌표만 믿지 않고, 운전자가 눈으로 확인할 단서(몇 번째, 간판, 사진)를 함께 가진다. */
data class Entrance(
    val id: String,
    val kind: EntranceKind,
    val lat: Double,
    val lng: Double,
    /** 주로 들어오는 방향(북=0, 동=90). 이 방향 기준으로 도로 쪽과 순서를 센다 */
    val approachBearingDeg: Double,
    val roadSide: RoadSide,
    /** 주 접근 방향에서 몇 번째 경사로인지 (1부터) */
    val orderFromApproach: Int,
    val landmark: String,
    /** 음성으로 읽을 한 문장 */
    val speech: String,
    val heightLimitM: Double?,
    val feeText: String?,
    val hoursText: String?,
    /** 운전자 시점 사진. 입구 30~50m 앞 차선에서 진행 방향 앞을 비스듬히 (300m 전 화면). 촬영법: docs/data-collection.md 4절 */
    val photoFar: String?,
    /** 운전자 시점 사진. 꺾기 직전 5~10m 앞에서 입구를 비스듬히 (입구 앞 화면). 정면 사진이 아니다 */
    val photoNear: String?,
    /** 바로 옆에 있어서 헷갈리는 다른 건물 입구 */
    val confusers: List<Confuser>,
    /** 밤에 찍은 사진(선택). 밤 모드일 때 있으면 이쪽을 보여 준다. 밤에는 간판 불빛이 달라 낮 사진으로는 알아보기 어렵다 */
    val photoFarNight: String? = null,
    val photoNearNight: String? = null,
    /** 요일·시간별 운영 상태(유료/무료 개방/폐쇄). hoursText 는 사람이 읽는 글이고, 이것은 앱이 지금 시각과 견주는 표다. 없으면 판단하지 않는다 */
    val schedule: OpeningHours? = null,
) {
    val canEnter: Boolean get() = kind != EntranceKind.OUT
}

/**
 * 헷갈리는 다른 건물 입구. 목적지 앞쪽(먼저 나오는 경사로)이 기본이고,
 * after=true 면 목적지를 지나치면 바로 나오는 뒤쪽 경사로다. 지나친 직후가 엉뚱한 곳에 들어가기 가장 쉬운 순간이라 따로 경고한다.
 */
data class Confuser(
    val description: String,
    val lat: Double,
    val lng: Double,
    val after: Boolean = false,
)

/** 목적지보다 먼저 나오는 헷갈리는 입구 */
val Entrance.confuserBefore: Confuser? get() = confusers.firstOrNull { !it.after }

/** 목적지를 지나치면 바로 나오는 헷갈리는 입구 */
val Entrance.confuserAfter: Confuser? get() = confusers.firstOrNull { it.after }
