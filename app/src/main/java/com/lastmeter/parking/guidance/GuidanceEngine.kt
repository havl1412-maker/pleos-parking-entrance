package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.Facility

/** 위치 한 번 */
data class Fix(val lat: Double, val lng: Double, val timeMs: Long)

/** 화면에 보여 줄 안내 상태 */
sealed interface GuidanceState {
    /** 목적지가 없거나 아직 멀다 */
    data object Idle : GuidanceState

    /**
     * 입구에 닿기 약 30초 전(속도를 모르면 300m 안): 사진과 단서로 미리 보여 주기.
     * otherDirection: 데이터에 적어 둔 진입 방향과 반대쪽에서 오는 중. 이때는 "오른쪽 두 번째" 같은 순서가 맞지 않는다
     */
    data class Approaching(val facility: Facility, val entrance: Entrance, val distanceM: Double, val otherDirection: Boolean = false) : GuidanceState

    /** 입구 바로 앞: 큰 화살표, 몇 번째인지, 헷갈리는 옆 입구 경고 */
    data class AtEntrance(val facility: Facility, val entrance: Entrance, val distanceM: Double, val otherDirection: Boolean = false) : GuidanceState

    /** 입구에 가까워졌다가 다시 멀어짐 */
    data class Passed(val facility: Facility, val entrance: Entrance, val distanceM: Double, val otherDirection: Boolean = false) : GuidanceState

    /** 입구 근처에서 P단 */
    data class Parked(val facility: Facility, val entrance: Entrance, val likelyOtherBuilding: Boolean) : GuidanceState
}

/** 상태가 바뀔 때 한 번만 내보내는 음성 안내 */
sealed interface GuidanceEvent {
    data class SpeakPreview(val facility: Facility, val entrance: Entrance, val otherDirection: Boolean = false) : GuidanceEvent
    data class SpeakConfuserWarning(val entrance: Entrance, val otherDirection: Boolean = false) : GuidanceEvent
    data class SpeakPassed(val entrance: Entrance, val otherDirection: Boolean = false) : GuidanceEvent
}

/**
 * 입구 안내 판단 로직. Android·Pleos SDK에 의존하지 않는 순수 코틀린이라 단위 테스트로 검증한다.
 *
 * 핵심 생각: 도심에서는 GPS가 수십 m 틀릴 수 있어서, "몇 m 앞에서 우회전"처럼 좌표만 믿는 안내를 하지 않는다.
 * 입구에 닿기 약 30초 전에 사진과 단서로 미리 보여 주고, 입구 앞에서는 데이터에 미리 적어 둔 "도로 오른쪽, 두 번째 경사로"를 보여 준다.
 *
 * 미리 보기 시점은 거리가 아니라 시간으로 맞춘다. 같은 300m 라도 시속 60km 면 18초, 시속 20km 면 54초라서
 * 빠르면 볼 틈이 없고 느리면 너무 일찍 떠서 잊는다. 그래서 최근 위치로 속도를 구해 "속도 × 30초" 거리에서 띄운다.
 */
class GuidanceEngine(private val config: Config = Config()) {

    data class Config(
        /** 속도를 모를 때(첫 위치, 위치가 튄 직후) 미리 보여 주기 시작 거리 */
        val approachM: Double = 300.0,
        /** 입구에 닿기 몇 초 전에 미리 보여 줄지. 음성(운영 시간·높이 경고가 붙으면 10초 넘음)을 다 듣고 사진을 볼 시간 */
        val previewLeadSec: Double = 30.0,
        /** 아무리 느려도 이 거리에서는 미리 보여 준다 (입구 앞 안내 80m 보다 충분히 앞) */
        val approachMinM: Double = 150.0,
        /** 아무리 빨라도 이 거리보다 먼저 띄우지 않는다 (되돌림 거리 600m 보다 작아야 한다). 시속 60km × 30초 = 500m */
        val approachMaxM: Double = 500.0,
        /** 속도를 재는 구간. 최근 이 시간 동안 움직인 직선 거리로 구한다 (한 번의 GPS 떨림에 흔들리지 않게) */
        val speedWindowSec: Double = 5.0,
        /** 이 시간 이상 쌓여야 속도를 믿는다 */
        val speedMinSpanSec: Double = 1.5,
        /** 이보다 빠르게 움직인 것으로 나오면 위치가 튄 것으로 보고 속도를 버린다 (약 시속 250km) */
        val maxPlausibleSpeedMps: Double = 70.0,
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
        /**
         * 달리는 방향이 데이터의 진입 방향과 이만큼(도) 넘게 다르면 반대쪽에서 오는 것으로 본다.
         * 같은 길을 반대로 달리면 약 180도. 옆길에서 꺾어 들어오는 중(약 90도)은 아직 꺾기 전일 수 있어 그대로 안내한다
         */
        val otherDirectionDeg: Double = 120.0,
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
    /** 속도 계산용 최근 위치들 */
    private val recentFixes = ArrayDeque<Fix>()
    /** 최근 위치로 구한 속도(m/s). 아직 모르면 null */
    var speedMps: Double? = null
        private set
    /** 미리 보기를 한 번 띄웠으면 속도가 줄어도(신호 대기 등) 내리지 않는다 */
    private var previewLatched = false
    /**
     * 이 목적지에서 주차까지 마쳤는지. 주차하고 다시 출발하면(P → D) 입구 앞을 지나며 안내가 또 나오지 않게,
     * 입구에서 멀리(resetM) 벗어나거나 목적지가 바뀔 때까지 조용히 있는다
     */
    private var finished = false
    /**
     * 비가 올 때처럼 더 일찍 알려 줘야 할 때 미리 보기 시점에 더하는 시간(초).
     * 와이퍼 신호와 잇는 것은 아직 하지 않았다: Wiper API 가 받는 WindowArea 값을 공식 문서에서 확인하지 못했다 (docs/sdk-check.md 10절).
     */
    var extraLeadSec: Double = 0.0

    /** 안내할 목적지 시설을 정한다. 어느 입구로 안내할지는 가까워질 때 진입 방향을 보고 정한다 */
    /**
     * 내비 목적지를 이 입구로 바꿔 두었으면, 방향이 맞지 않는 게 아닌 한 이 입구로 안내한다.
     * (앱이 내비를 in-1 로 보냈는데 화면은 in-2 를 안내하는 어긋남을 막는다)
     */
    var preferredEntranceId: String? = null

    fun setTarget(target: Facility?) {
        facility = target
        preferredEntranceId = null
        candidates = target?.entrances?.filter { it.canEnter }.orEmpty()
        entrance = candidates.firstOrNull()
        bearingAnchor = null
        travelBearingDeg = null
        finished = false
        resetProgress()
        state = GuidanceState.Idle
    }

    /** 지금 달리는 방향이 이 입구의 진입 방향과 반대쪽인지. 방향을 아직 모르면(멈춰 있음) 아니라고 본다 */
    fun isOtherDirection(e: Entrance): Boolean {
        val heading = travelBearingDeg ?: return false
        return kotlin.math.abs(Geo.angleDiffDeg(heading, e.approachBearingDeg)) > config.otherDirectionDeg
    }

    /** 위치가 올 때마다 최근 몇 초 동안의 이동 거리로 속도를 다시 구한다 */
    private fun updateSpeed(fix: Fix) {
        val last = recentFixes.lastOrNull()
        if (last != null) {
            val dt = (fix.timeMs - last.timeMs) / 1000.0
            // 같은 시각이나 몇 ms 차이로 위치가 겹쳐 오면 속도 계산에서 뺀다 (작은 dt 로 나누면 속도가 수백 m/s 로 튄다)
            if (dt < 0.5) return
            val step = Geo.distanceM(last.lat, last.lng, fix.lat, fix.lng) / dt
            if (step > config.maxPlausibleSpeedMps) {
                // 위치가 튀었다(터널을 나온 직후, 에뮬레이터에서 좌표를 옮긴 직후). 속도를 처음부터 다시 잰다
                recentFixes.clear()
                speedMps = null
            }
        }
        recentFixes.addLast(fix)
        while (recentFixes.size > 1 && fix.timeMs - recentFixes.first().timeMs > config.speedWindowSec * 1000) {
            recentFixes.removeFirst()
        }
        val first = recentFixes.first()
        val span = (fix.timeMs - first.timeMs) / 1000.0
        // 위치가 한동안 끊겼다 다시 오면 창에 하나만 남는다. 그때는 예전 속도를 쓰지 않고 모른다고 본다
        speedMps = if (span >= config.speedMinSpanSec) Geo.distanceM(first.lat, first.lng, fix.lat, fix.lng) / span else null
    }

    /** 지금 속도에서 미리 보기를 시작할 거리(m). 속도 × 앞당길 시간, 단 150~500m 사이. 속도를 모르면 300m */
    fun previewDistanceM(): Double {
        val v = speedMps ?: return config.approachM
        return (v * (config.previewLeadSec + extraLeadSec)).coerceIn(config.approachMinM, config.approachMaxM)
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
     * 미리 보기 거리 안에 들어서는 순간 "지금 달리는 방향"과 주 접근 방향이 가장 잘 맞는 입구로 정한다.
     */
    private fun pickEntrance(fix: Fix): Entrance? {
        if (candidates.size <= 1) return entrance
        if (entranceLocked) return reconsiderLocked(fix)
        val nearest = candidates.minByOrNull { Geo.distanceM(fix.lat, fix.lng, it.lat, it.lng) } ?: return null
        if (Geo.distanceM(fix.lat, fix.lng, nearest.lat, nearest.lng) > previewDistanceM()) return nearest
        val heading = travelBearingDeg
        val preferred = candidates.firstOrNull { it.id == preferredEntranceId }?.takeIf { !isOtherDirection(it) }
        val chosen = preferred ?: if (heading == null) nearest else bestForHeading(fix, heading) ?: nearest
        entranceLocked = true
        return chosen
    }

    private fun bestForHeading(fix: Fix, heading: Double): Entrance? = candidates
        .filter { Geo.distanceM(fix.lat, fix.lng, it.lat, it.lng) <= config.resetM }
        .minByOrNull { kotlin.math.abs(Geo.angleDiffDeg(heading, it.approachBearingDeg)) }

    /**
     * 미리 보기 거리(최대 500m)에서 정한 입구가, 그 뒤 달리는 방향을 보니 반대쪽이면 방향이 맞는 다른 입구로 바꾼다.
     * 멀리서는 옆길에 있다가 꺾어 들어오는 일이 많고, 정할 때 방향을 몰랐을 수도 있다. 입구 앞(80m)에 들어선 뒤에는 바꾸지 않는다
     */
    private fun reconsiderLocked(fix: Fix): Entrance? {
        val current = entrance ?: return null
        val heading = travelBearingDeg ?: return current
        if (Geo.distanceM(fix.lat, fix.lng, current.lat, current.lng) <= config.atEntranceM) return current
        if (!isOtherDirection(current)) return current
        val better = bestForHeading(fix, heading)?.takeIf { it.id != current.id && !isOtherDirection(it) } ?: return current
        // 새 입구로 처음부터 안내한다 (미리 보기 음성도 새 입구 기준으로 다시)
        resetProgress()
        entranceLocked = true
        return better
    }

    val target: Facility? get() = facility

    fun onFix(fix: Fix): List<GuidanceEvent> {
        lastFix = fix
        updateTravelBearing(fix)
        updateSpeed(fix)
        val f = facility ?: return emptyList()
        if (state is GuidanceState.Parked) return emptyList()
        val e = pickEntrance(fix) ?: return emptyList()
        entrance = e

        val d = Geo.distanceM(fix.lat, fix.lng, e.lat, e.lng)
        val events = mutableListOf<GuidanceEvent>()

        if (d > config.resetM) {
            finished = false
            resetProgress()
            state = GuidanceState.Idle
            return events
        }
        if (finished) {
            // 주차를 마치고 나가는 중: 입구 앞을 다시 지나도 안내하지 않는다
            state = GuidanceState.Idle
            return events
        }
        if (d < minDistanceM) minDistanceM = d
        // 방향은 매번 다시 본다. 멀리서는 옆길에 있다가 입구 길로 꺾어 들어오는 경우가 많다
        val other = isOtherDirection(e)

        val passed = minDistanceM <= config.passedMinApproachM && d >= minDistanceM + config.passedMarginM
        state = when {
            passed -> {
                if (!passedSpoken) {
                    passedSpoken = true
                    events += GuidanceEvent.SpeakPassed(e, other)
                }
                GuidanceState.Passed(f, e, d, other)
            }
            d <= config.atEntranceM -> {
                // 반대쪽에서 오면 헷갈리는 입구가 없어도 "간판을 보고 찾으세요"를 한 번 말한다
                if (!confuserSpoken && (e.confusers.isNotEmpty() || other)) {
                    confuserSpoken = true
                    events += GuidanceEvent.SpeakConfuserWarning(e, other)
                }
                GuidanceState.AtEntrance(f, e, d, other)
            }
            previewLatched || d <= previewDistanceM() -> {
                previewLatched = true
                if (!previewSpoken) {
                    previewSpoken = true
                    events += GuidanceEvent.SpeakPreview(f, e, other)
                }
                GuidanceState.Approaching(f, e, d, other)
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
            // 이 목적지에서 이미 주차를 마쳤으면(주차장 안에서 다시 P, 출구 정산기 앞 P) 다시 묻지 않는다
            if (finished) return
            val fix = lastFix ?: return
            val dEntrance = Geo.distanceM(fix.lat, fix.lng, e.lat, e.lng)
            if (dEntrance > config.parkedRadiusM) return
            // 입구 앞(80m)까지 와 본 적이 없으면 주차가 아니다. 입구 120m 전 신호 대기에서 P 를 넣는 경우 등
            if (minDistanceM > config.atEntranceM) return
            // 목적지 입구보다 헷갈리는 옆 건물 입구에 더 가까우면 다른 건물 주차장일 수 있다
            val nearerConfuser = e.confusers.any { c ->
                Geo.distanceM(fix.lat, fix.lng, c.lat, c.lng) < dEntrance
            }
            state = GuidanceState.Parked(f, e, likelyOtherBuilding = nearerConfuser)
        } else {
            val parked = state as? GuidanceState.Parked ?: return
            resetProgress()
            state = GuidanceState.Idle
            // 맞는 건물에 주차했으면 안내를 끝낸다. 옆 건물일 수 있으면 다시 찾으러 나가는 것이니 안내를 다시 한다
            finished = !parked.likelyOtherBuilding
        }
    }

    private fun resetProgress() {
        entranceLocked = false
        minDistanceM = Double.MAX_VALUE
        previewLatched = false
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
