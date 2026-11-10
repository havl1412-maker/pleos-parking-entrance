package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.Facility

/** 위치 한 번 */
data class Fix(val lat: Double, val lng: Double, val timeMs: Long)

/** 화면에 보여 줄 안내 상태 */
sealed interface GuidanceState {
    /** 목적지가 없거나 아직 멀다 */
    data object Idle : GuidanceState

    /** 입구 300m 안: 사진과 단서로 미리 보여 주기 */
    data class Approaching(val facility: Facility, val entrance: Entrance, val distanceM: Double) : GuidanceState

    /** 입구 바로 앞: 큰 화살표, 몇 번째인지, 헷갈리는 옆 입구 경고 */
    data class AtEntrance(val facility: Facility, val entrance: Entrance, val distanceM: Double) : GuidanceState

    /** 입구에 가까워졌다가 다시 멀어짐 */
    data class Passed(val facility: Facility, val entrance: Entrance, val distanceM: Double) : GuidanceState

    /** 입구 근처에서 P단 */
    data class Parked(val facility: Facility, val entrance: Entrance, val likelyOtherBuilding: Boolean) : GuidanceState
}

/** 상태가 바뀔 때 한 번만 내보내는 음성 안내 */
sealed interface GuidanceEvent {
    data class SpeakPreview(val facility: Facility, val entrance: Entrance) : GuidanceEvent
    data class SpeakConfuserWarning(val entrance: Entrance) : GuidanceEvent
    data class SpeakPassed(val entrance: Entrance) : GuidanceEvent
}

/**
 * 입구 안내 판단 로직. Android·Pleos SDK에 의존하지 않는 순수 코틀린이라 단위 테스트로 검증한다.
 *
 * 핵심 생각: 도심에서는 GPS가 수십 m 틀릴 수 있어서, "몇 m 앞에서 우회전"처럼 좌표만 믿는 안내를 하지 않는다.
 * 300m 전에 사진과 단서로 미리 보여 주고, 입구 앞에서는 데이터에 미리 적어 둔 "도로 오른쪽, 두 번째 경사로"를 보여 준다.
 */
class GuidanceEngine(private val config: Config = Config()) {

    data class Config(
        /** 미리 보여 주기 시작 거리 */
        val approachM: Double = 300.0,
        /** 입구 앞 안내로 바꾸는 거리 */
        val atEntranceM: Double = 80.0,
        /** 가장 가까웠던 거리보다 이만큼 멀어지면 지나친 것으로 본다 */
        val passedMarginM: Double = 40.0,
        /** "지나침"을 판단하려면 적어도 이 거리 안까지는 들어왔어야 한다 */
        val passedMinApproachM: Double = 120.0,
        /** P단을 입구 근처 주차로 인정하는 거리 */
        val parkedRadiusM: Double = 150.0,
        /** 이 거리보다 멀어지면 안내를 처음 상태로 되돌린다 */
        val resetM: Double = 600.0,
    )

    var state: GuidanceState = GuidanceState.Idle
        private set

    private var facility: Facility? = null
    private var entrance: Entrance? = null
    private var lastFix: Fix? = null
    private var minDistanceM = Double.MAX_VALUE
    private var previewSpoken = false
    private var confuserSpoken = false
    private var passedSpoken = false
    /** 들어갈 수 있는 입구들. 300m 안에 들어설 때 진입 방향에 맞는 하나로 정한다 */
    private var candidates: List<Entrance> = emptyList()
    private var entranceLocked = false
    /** 진행 방향 계산용: 이 위치에서 15m 이상 움직였을 때의 방위를 진행 방향으로 본다 (GPS 떨림 무시) */
    private var bearingAnchor: Fix? = null
    private var travelBearingDeg: Double? = null

    /** 안내할 목적지 시설을 정한다. 어느 입구로 안내할지는 가까워질 때 진입 방향을 보고 정한다 */
    fun setTarget(target: Facility?) {
        facility = target
        candidates = target?.entrances?.filter { it.canEnter }.orEmpty()
        entrance = candidates.firstOrNull()
        bearingAnchor = null
        travelBearingDeg = null
        resetProgress()
        state = GuidanceState.Idle
    }

    private fun updateTravelBearing(fix: Fix) {
        val anchor = bearingAnchor
        if (anchor == null) {
            bearingAnchor = fix
        } else if (Geo.distanceM(anchor.lat, anchor.lng, fix.lat, fix.lng) >= 15.0) {
            travelBearingDeg = Geo.bearingDeg(anchor.lat, anchor.lng, fix.lat, fix.lng)
            bearingAnchor = fix
        }
    }

    /**
     * 아직 입구를 정하지 않았으면 가장 가까운 입구를 기준으로 거리를 재고,
     * 300m 안에 들어서는 순간 "지금 달리는 방향"과 주 접근 방향이 가장 잘 맞는 입구로 정한다.
     */
    private fun pickEntrance(fix: Fix): Entrance? {
        if (entranceLocked || candidates.size <= 1) return entrance
        val nearest = candidates.minByOrNull { Geo.distanceM(fix.lat, fix.lng, it.lat, it.lng) } ?: return null
        if (Geo.distanceM(fix.lat, fix.lng, nearest.lat, nearest.lng) > config.approachM) return nearest
        val heading = travelBearingDeg
        val chosen = if (heading == null) nearest else {
            candidates
                .filter { Geo.distanceM(fix.lat, fix.lng, it.lat, it.lng) <= config.resetM }
                .minByOrNull { kotlin.math.abs(Geo.angleDiffDeg(heading, it.approachBearingDeg)) } ?: nearest
        }
        entranceLocked = true
        return chosen
    }

    val target: Facility? get() = facility

    fun onFix(fix: Fix): List<GuidanceEvent> {
        lastFix = fix
        updateTravelBearing(fix)
        val f = facility ?: return emptyList()
        if (state is GuidanceState.Parked) return emptyList()
        val e = pickEntrance(fix) ?: return emptyList()
        entrance = e

        val d = Geo.distanceM(fix.lat, fix.lng, e.lat, e.lng)
        val events = mutableListOf<GuidanceEvent>()

        if (d > config.resetM) {
            resetProgress()
            state = GuidanceState.Idle
            return events
        }
        if (d < minDistanceM) minDistanceM = d

        val passed = minDistanceM <= config.passedMinApproachM && d >= minDistanceM + config.passedMarginM
        state = when {
            passed -> {
                if (!passedSpoken) {
                    passedSpoken = true
                    events += GuidanceEvent.SpeakPassed(e)
                }
                GuidanceState.Passed(f, e, d)
            }
            d <= config.atEntranceM -> {
                if (!confuserSpoken && e.confusers.isNotEmpty()) {
                    confuserSpoken = true
                    events += GuidanceEvent.SpeakConfuserWarning(e)
                }
                GuidanceState.AtEntrance(f, e, d)
            }
            d <= config.approachM -> {
                if (!previewSpoken) {
                    previewSpoken = true
                    events += GuidanceEvent.SpeakPreview(f, e)
                }
                GuidanceState.Approaching(f, e, d)
            }
            else -> GuidanceState.Idle
        }
        return events
    }

    /** 기어 변화. P단이면 입구 근처인지 보고 주차 완료 화면으로, P단에서 풀리면 다시 안내 */
    fun onGear(isPark: Boolean) {
        val f = facility ?: return
        val e = entrance ?: return
        if (isPark) {
            val fix = lastFix ?: return
            val dEntrance = Geo.distanceM(fix.lat, fix.lng, e.lat, e.lng)
            if (dEntrance > config.parkedRadiusM) return
            // 목적지 입구보다 헷갈리는 옆 건물 입구에 더 가까우면 다른 건물 주차장일 수 있다
            val nearerConfuser = e.confusers.any { c ->
                Geo.distanceM(fix.lat, fix.lng, c.lat, c.lng) < dEntrance
            }
            state = GuidanceState.Parked(f, e, likelyOtherBuilding = nearerConfuser)
        } else if (state is GuidanceState.Parked) {
            resetProgress()
            state = GuidanceState.Idle
        }
    }

    private fun resetProgress() {
        entranceLocked = false
        minDistanceM = Double.MAX_VALUE
        previewSpoken = false
        confuserSpoken = false
        passedSpoken = false
    }

    companion object {
        /** 1 → "첫", 2 → "두" ... 음성과 화면에 쓰는 순서 표현 */
        fun ordinalKo(n: Int): String = when (n) {
            1 -> "첫"
            2 -> "두"
            3 -> "세"
            4 -> "네"
            5 -> "다섯"
            else -> "$n"
        }
    }
}
