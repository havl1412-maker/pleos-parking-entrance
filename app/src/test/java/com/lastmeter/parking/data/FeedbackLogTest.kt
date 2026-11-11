package com.lastmeter.parking.data

import com.lastmeter.parking.guidance.SpeechTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackLogTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `기록이 없으면 주의 표시를 하지 않는다`() {
        val s = FeedbackLog().summary("e1", now)
        assertEquals(FeedbackSummary(0, 0, null), s)
        assertFalse(s.caution)
        assertNull(SpeechTexts.feedbackLine(s))
    }

    @Test
    fun `아니었어요가 맞았어요보다 적지 않으면 다음 안내 때 주의`() {
        val log = FeedbackLog()
        log.add(FeedbackRecord("e1", now - 3 * day, correct = false, reason = FeedbackReason.WRONG_RAMP))
        assertTrue(log.summary("e1", now).caution)
        assertEquals("지난번에 다른 경사로였다고 기록했어요", SpeechTexts.feedbackLine(log.summary("e1", now)))

        log.add(FeedbackRecord("e1", now - 2 * day, correct = true))
        assertTrue(log.summary("e1", now).caution) // 1 대 1 이면 아직 주의

        log.add(FeedbackRecord("e1", now - 1 * day, correct = true))
        assertFalse(log.summary("e1", now).caution) // 그 뒤로 두 번 맞았으면 주의를 푼다
    }

    @Test
    fun `다른 입구 기록과 섞이지 않는다`() {
        val log = FeedbackLog(listOf(FeedbackRecord("e2", now, correct = false, reason = FeedbackReason.CLOSED)))
        assertFalse(log.summary("e1", now).caution)
        assertEquals(FeedbackReason.CLOSED, log.summary("e2", now).lastWrongReason)
    }

    @Test
    fun `90일이 지난 기록은 세지 않는다`() {
        val log = FeedbackLog(listOf(FeedbackRecord("e1", now - 91 * day, correct = false, reason = FeedbackReason.WRONG_RAMP)))
        assertEquals(FeedbackSummary(0, 0, null), log.summary("e1", now))
    }

    @Test
    fun `가장 최근에 고른 이유를 쓴다`() {
        val log = FeedbackLog()
        log.add(FeedbackRecord("e1", now - 5 * day, correct = false, reason = FeedbackReason.WRONG_RAMP))
        log.add(FeedbackRecord("e1", now - 1 * day, correct = false, reason = FeedbackReason.LOOKS_DIFFERENT))
        val s = log.summary("e1", now)
        assertEquals(FeedbackReason.LOOKS_DIFFERENT, s.lastWrongReason)
        assertEquals("지난번에 사진·설명과 달랐다고 기록했어요", SpeechTexts.feedbackLine(s))
        assertEquals("지난번에 사진, 설명과 달랐다고 기록하셨어요. 간판을 한 번 더 확인하세요.", SpeechTexts.feedbackSpeech(s))
    }

    @Test
    fun `저장했다가 다시 읽어도 같다`() {
        val log = FeedbackLog()
        log.add(FeedbackRecord("dev-001-in-1", now - day, correct = true))
        log.add(FeedbackRecord("dev-001-in-1", now, correct = false, reason = FeedbackReason.CLOSED))
        log.add(FeedbackRecord("dev-003-in-1", now, correct = false)) // 이유를 고르지 않은 기록
        assertEquals(log.records, FeedbackLog.decode(log.encode()).records)
    }

    @Test
    fun `깨진 줄은 버리고 나머지는 읽는다`() {
        val log = FeedbackLog.decode("e1\t${now}\t0\tWRONG_RAMP\n깨진 줄\n\ne1\t${now}\t0\t없어진이유")
        assertEquals(2, log.records.size)
        assertNull(log.records[1].reason)
    }

    @Test
    fun `기록은 최근 300개까지만 둔다`() {
        val log = FeedbackLog()
        repeat(FeedbackLog.MAX_RECORDS + 5) { log.add(FeedbackRecord("e$it", now, correct = true)) }
        assertEquals(FeedbackLog.MAX_RECORDS, log.records.size)
        assertEquals("e5", log.records.first().entranceId)
    }
}
