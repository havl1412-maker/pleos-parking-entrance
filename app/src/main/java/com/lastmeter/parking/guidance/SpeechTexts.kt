package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.RoadSide

/**
 * 음성·화면 문구. 내비가 아닌 앱이라 "몇 m 앞 우회전" 같은 경로 안내는 하지 않고,
 * 어느 입구인지 알아보게 하는 설명만 한다. (Pleos 단일 경로 안내 정책)
 */
object SpeechTexts {
    fun preview(event: GuidanceEvent.SpeakPreview): String =
        "${event.facility.name} 주차장 입구를 알려 드릴게요. ${event.entrance.speech}"

    fun confuser(event: GuidanceEvent.SpeakConfuserWarning): String {
        val e = event.entrance
        val warning = e.confusers.first().description
        return "$warning. ${sideKo(e.roadSide)} ${GuidanceEngine.ordinalKo(e.orderFromApproach)} 번째 경사로로 들어가세요."
    }

    fun passed(): String = "입구를 지나쳤어요. 내비가 다시 안내할 거예요."

    fun tooTall(limitM: Double): String = "주의하세요. 높이 제한이 ${limitM}미터라 이 차는 들어가기 어려워요."

    fun sideKo(side: RoadSide): String = if (side == RoadSide.RIGHT) "오른쪽" else "왼쪽"

    fun orderLine(side: RoadSide, order: Int): String =
        "${sideKo(side)} · ${GuidanceEngine.ordinalKo(order)} 번째 경사로"

    /** 화면에 보여 줄 대략적인 거리. GPS 오차가 있어 촘촘하게 보여 주지 않는다 */
    fun roughDistance(m: Double): String = when {
        m >= 100 -> "약 ${(Math.round(m / 50.0) * 50).toInt()}m"
        m >= 30 -> "약 ${(Math.round(m / 10.0) * 10).toInt()}m"
        else -> "바로 앞"
    }
}
