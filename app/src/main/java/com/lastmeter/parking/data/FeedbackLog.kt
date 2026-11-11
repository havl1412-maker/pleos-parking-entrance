package com.lastmeter.parking.data

/** "아니었어요"를 누른 뒤 고르는 이유. 입구 데이터의 어느 칸을 고쳐야 하는지 알려 준다 */
enum class FeedbackReason(val label: String) {
    /** 몇 번째 경사로인지(orderFromApproach)나 헷갈리는 입구 정보가 틀렸다 */
    WRONG_RAMP("다른 경사로였어요"),
    /** 운영 시간표(schedule)가 틀렸다 */
    CLOSED("입구가 닫혀 있었어요"),
    /** 사진이나 눈에 띄는 단서(landmark)가 바뀌었다 */
    LOOKS_DIFFERENT("사진·설명과 달랐어요"),
}

/** 주차한 뒤 운전자가 남긴 답 하나 */
data class FeedbackRecord(
    val entranceId: String,
    val timeMs: Long,
    val correct: Boolean,
    val reason: FeedbackReason? = null,
)

/** 한 입구에 대한 최근 기록 요약 */
data class FeedbackSummary(val correct: Int, val wrong: Int, val lastWrongReason: FeedbackReason?) {
    /** 다음 안내 때 "지난번에 맞지 않았다"고 알려 줄지: 틀렸다는 기록이 있고, 맞았다는 기록보다 적지 않을 때 */
    val caution: Boolean get() = wrong > 0 && wrong >= correct
}

/**
 * 입구 안내가 맞았는지에 대한 기록 모음. 지금은 이 차 안에만 저장한다(서버 전송은 동의를 받는 구조를 만든 뒤, docs/roadmap.md).
 * 안드로이드에 의존하지 않는 순수 코틀린이라 단위 테스트로 검증한다. 저장은 encode()/decode() 로 글자 한 덩어리로 한다.
 */
class FeedbackLog(records: List<FeedbackRecord> = emptyList()) {
    private val items = records.takeLast(MAX_RECORDS).toMutableList()

    val records: List<FeedbackRecord> get() = items

    fun add(record: FeedbackRecord) {
        items += record
        while (items.size > MAX_RECORDS) items.removeAt(0)
    }

    /** 최근 90일 기록만 센다. 오래된 기록은 그사이 입구나 데이터가 바뀌었을 수 있다 */
    fun summary(entranceId: String, nowMs: Long): FeedbackSummary {
        val recent = items.filter { it.entranceId == entranceId && nowMs - it.timeMs <= WINDOW_MS }
        return FeedbackSummary(
            correct = recent.count { it.correct },
            wrong = recent.count { !it.correct },
            lastWrongReason = recent.lastOrNull { !it.correct }?.reason,
        )
    }

    /** 한 줄에 기록 하나: 입구ID, 시각, 맞음(1)/아님(0), 이유 */
    fun encode(): String = items.joinToString("\n") {
        listOf(it.entranceId, it.timeMs, if (it.correct) 1 else 0, it.reason?.name ?: "").joinToString("\t")
    }

    companion object {
        const val MAX_RECORDS = 300
        const val WINDOW_MS = 90L * 24 * 60 * 60 * 1000

        /** 읽을 수 없는 줄은 버린다 (이유 이름이 바뀌었으면 이유만 비운다) */
        fun decode(text: String): FeedbackLog = FeedbackLog(
            text.lines().mapNotNull { line ->
                val p = line.split('\t')
                val time = p.getOrNull(1)?.toLongOrNull()
                if (p.size < 3 || p[0].isBlank() || time == null) null
                else FeedbackRecord(p[0], time, p[2] == "1", FeedbackReason.values().firstOrNull { it.name == p.getOrNull(3) })
            },
        )
    }
}
