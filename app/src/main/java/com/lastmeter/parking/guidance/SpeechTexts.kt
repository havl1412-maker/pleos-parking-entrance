package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.Access
import com.lastmeter.parking.data.AccessStatus
import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.FeedbackReason
import com.lastmeter.parking.data.FeedbackSummary
import com.lastmeter.parking.data.RoadSide
import com.lastmeter.parking.data.confuserAfter
import com.lastmeter.parking.data.confuserBefore
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 음성·화면 문구. 내비가 아닌 앱이라 "몇 m 앞 우회전" 같은 경로 안내는 하지 않고,
 * 어느 입구인지 알아보게 하는 설명만 한다. (Pleos 단일 경로 안내 정책)
 */
object SpeechTexts {
    /** 미리 보기 음성. 문을 닫았으면 그것부터, 무료 개방이나 곧 닫는 것은 끝에 덧붙인다 */
    fun preview(event: GuidanceEvent.SpeakPreview, access: Access? = null, now: LocalDateTime? = null): String {
        val lot = lotName(event.facility.name)
        // 반대쪽에서 오면 데이터에 적은 "오른쪽, 편의점 바로 다음" 이 맞지 않는다
        val how = if (event.otherDirection) OTHER_DIRECTION else event.entrance.speech
        val base = "$lot 입구를 알려 드릴게요. $how"
        if (access == null || now == null) return base
        return when {
            access.status == AccessStatus.CLOSED -> "주의하세요. ${lot}은 지금 운영 시간이 아니라 입구가 닫혀 있어요. $how"
            access.status == AccessStatus.FREE ->
                "$base 지금은 무료로 열려 있어요." + if (closingSoon(access, now)) " ${clockSpoken(access.closesAt!!, now)}에 문을 닫아요." else ""
            closingSoon(access, now) -> "$base ${clockSpoken(access.closesAt!!, now)}에 문을 닫아요."
            else -> base
        }
    }

    /** "○○빌딩" → "○○빌딩 주차장", 이름이 이미 주차장으로 끝나면("○○ 공영 주차장") 그대로 */
    fun lotName(facilityName: String): String =
        if (facilityName.trimEnd().endsWith("주차장")) facilityName.trimEnd() else "${facilityName.trimEnd()} 주차장"

    // ---------- 운영 시간 ----------

    /** 문 닫는 시각이 이 시간(분) 안이면 미리 알려 준다. 주차하고 볼일을 보는 사이에 닫힐 수 있는 시간 */
    const val CLOSING_SOON_MIN = 120L

    fun closingSoon(access: Access, now: LocalDateTime): Boolean {
        val closes = access.closesAt ?: return false
        return ChronoUnit.MINUTES.between(now, closes) <= CLOSING_SOON_MIN
    }

    /** 화면에 보여 줄 한 줄. 평소(유료 운영 중이고 한동안 안 닫음)에는 null */
    fun accessLine(access: Access, now: LocalDateTime): String? = when (access.status) {
        AccessStatus.CLOSED -> "지금은 문을 닫았어요" + (access.changesAt?.let { " · ${clock(it, now)}에 열어요" } ?: "")
        AccessStatus.FREE -> "지금은 무료 개방" + when {
            // 닫히는 것이 더 중요하다 (차가 갇힌다). 유료로 바뀐 뒤 곧 닫는 경우도 여기서 알린다
            closingSoon(access, now) -> " · ${clock(access.closesAt!!, now)}에 닫아요"
            access.nextStatus == AccessStatus.PAID -> " · ${clock(access.changesAt!!, now)}부터 유료"
            access.nextStatus == AccessStatus.CLOSED -> " · ${clock(access.changesAt!!, now)}에 닫아요"
            else -> ""
        }
        AccessStatus.PAID -> if (closingSoon(access, now)) "${clock(access.closesAt!!, now)}에 문을 닫아요" else null
    }

    /** 주차 완료 화면의 "지금" 칸 */
    fun accessNow(access: Access): String = when (access.status) {
        AccessStatus.PAID -> "운영 중 (유료)"
        AccessStatus.FREE -> "무료 개방 중"
        AccessStatus.CLOSED -> "문 닫음"
    }

    private fun dayPrefix(t: LocalDateTime, now: LocalDateTime): String =
        when (ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())) {
            0L -> ""
            1L -> "내일 "
            else -> "${"월화수목금토일"[t.dayOfWeek.value - 1]}요일 "
        }

    private fun isNextMidnight(t: LocalDateTime, now: LocalDateTime): Boolean =
        t.hour == 0 && t.minute == 0 && ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate()) == 1L

    /** 화면용 시각: "22:00", "내일 07:00", "월요일 09:00", 오늘 밤 12시는 "자정" */
    fun clock(t: LocalDateTime, now: LocalDateTime): String =
        if (isNextMidnight(t, now)) "자정" else dayPrefix(t, now) + "%02d:%02d".format(t.hour, t.minute)

    /** 음성용 시각: "22시", "22시 30분", "내일 7시" */
    fun clockSpoken(t: LocalDateTime, now: LocalDateTime): String =
        if (isNextMidnight(t, now)) "자정" else dayPrefix(t, now) + "${t.hour}시" + if (t.minute != 0) " ${t.minute}분" else ""

    /** 반대쪽에서 올 때 쓰는 말. 순서·좌우를 말하지 않고 사진의 간판으로 찾게 한다 */
    const val OTHER_DIRECTION = "평소와 다른 방향에서 오고 있어서, 입구가 길 건너편에 있을 수 있어요. 사진의 간판을 보고 찾으세요."

    /** 입구 앞: 앞쪽 헷갈리는 입구 경고 → 들어갈 입구 → (있으면) 뒤쪽 헷갈리는 입구 경고. 할 일을 가운데 둔다 */
    fun confuser(event: GuidanceEvent.SpeakConfuserWarning): String {
        val e = event.entrance
        if (event.otherDirection) {
            // 반대쪽에서는 "첫 번째는 옆 건물" 같은 순서가 뒤집힌다
            return if (e.confusers.isNotEmpty()) "근처에 다른 건물 주차장 입구가 있어요. 간판을 확인하고 들어가세요."
            else "간판을 확인하고 들어가세요."
        }
        val parts = mutableListOf<String>()
        e.confuserBefore?.let { parts += it.description }
        parts += "${sideKo(e.roadSide)} ${GuidanceEngine.ordinalKo(e.orderFromApproach)} 번째 경사로로 들어가세요"
        e.confuserAfter?.let { parts += it.description }
        return parts.joinToString(". ", postfix = ".")
    }

    /**
     * 입구 앞 음성. 들어가면 안 되는 경우(차가 높이 제한에 걸림, 문이 닫힘)에는 "들어가세요"를 말하지 않고 그 경고만 한다.
     * 화면도 같은 경우에 경고 카드를 보여 준다
     */
    fun atEntrance(event: GuidanceEvent.SpeakConfuserWarning, facilityName: String, tooTallLimitM: Double?, closed: Boolean): String = when {
        tooTallLimitM != null -> tooTall(tooTallLimitM)
        closed -> "주의하세요. ${lotName(facilityName)}은 지금 문이 닫혀 있어요."
        else -> confuser(event)
    }

    /** 지나친 직후에는 바로 다음 경사로(다른 건물)로 잘못 들어가기 쉽다 */
    fun passed(entrance: Entrance? = null, otherDirection: Boolean = false): String =
        // 반대쪽에서 오면 "바로 다음 경사로"가 데이터의 뒤쪽 입구가 아니다
        if (!otherDirection && entrance?.confuserAfter != null) "입구를 지나쳤어요. 바로 다음 경사로는 다른 건물 주차장이니 들어가지 마세요. 내비가 다시 안내할 거예요."
        else "입구를 지나쳤어요. 내비가 다시 안내할 거예요."

    // ---------- 내비 목적지 바꾸기 ----------

    fun routeChanged(facilityName: String): String =
        "${lotName(facilityName)} 입구로 내비 목적지를 바꿨어요. 내비가 입구까지 안내할 거예요."

    // ---------- 지난 피드백 ----------

    private fun feedbackWhat(reason: FeedbackReason?): String = when (reason) {
        FeedbackReason.WRONG_RAMP -> "다른 경사로였다고"
        FeedbackReason.CLOSED -> "입구가 닫혀 있었다고"
        FeedbackReason.LOOKS_DIFFERENT -> "사진·설명과 달랐다고"
        null -> "안내가 맞지 않았다고"
    }

    /** 미리 보기 화면에 보여 줄 한 줄. 주의할 기록이 없으면 null */
    fun feedbackLine(summary: FeedbackSummary): String? =
        if (summary.caution) "지난번에 ${feedbackWhat(summary.lastWrongReason)} 기록했어요" else null

    fun feedbackSpeech(summary: FeedbackSummary): String? =
        if (summary.caution) "지난번에 ${feedbackWhat(summary.lastWrongReason).replace("·", ", ")} 기록하셨어요. 간판을 한 번 더 확인하세요." else null

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
