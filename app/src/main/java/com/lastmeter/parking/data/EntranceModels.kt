package com.lastmeter.parking.data

/** 주차장이 있는 건물(시설) 하나. 입구가 여러 개일 수 있다. */
data class Facility(
    val id: String,
    val name: String,
    val address: String,
    val entrances: List<Entrance>,
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
) {
    val canEnter: Boolean get() = kind != EntranceKind.OUT
}

data class Confuser(
    val description: String,
    val lat: Double,
    val lng: Double,
)
