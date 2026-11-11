package com.lastmeter.parking.guidance

import com.lastmeter.parking.data.AccessStatus
import com.lastmeter.parking.data.Confuser
import com.lastmeter.parking.data.Entrance
import com.lastmeter.parking.data.EntranceKind
import com.lastmeter.parking.data.Facility
import com.lastmeter.parking.data.HoursRule
import com.lastmeter.parking.data.OpeningHours
import com.lastmeter.parking.data.RoadSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SpeechTextsTest {

    private val before = Confuser("첫 번째 경사로는 옆 건물 주차장이에요", 37.4143, 127.0947)
    private val after = Confuser("세 번째 경사로는 다음 건물 주차장이에요", 37.41465, 127.0947, after = true)
    private val entrance = Entrance(
        id = "e1", kind = EntranceKind.IN, lat = 37.4145, lng = 127.0947,
        approachBearingDeg = 0.0, roadSide = RoadSide.RIGHT, orderFromApproach = 2,
        landmark = "편의점 바로 다음", speech = "오른쪽 두 번째 경사로예요.",
        heightLimitM = null, feeText = null, hoursText = null, photoFar = null, photoNear = null,
        confusers = listOf(before),
    )

    private fun confuserLine(e: Entrance) = SpeechTexts.confuser(GuidanceEvent.SpeakConfuserWarning(e))

    @Test
    fun `앞쪽 헷갈리는 입구만 있으면 경고 다음에 들어갈 입구`() {
        assertEquals("첫 번째 경사로는 옆 건물 주차장이에요. 오른쪽 두 번째 경사로로 들어가세요.", confuserLine(entrance))
    }

    @Test
    fun `뒤쪽 입구도 있으면 할 일을 가운데 두고 뒤쪽 경고를 덧붙인다`() {
        val e = entrance.copy(confusers = listOf(after, before)) // 순서가 섞여 있어도 앞쪽 → 할 일 → 뒤쪽
        assertEquals(
            "첫 번째 경사로는 옆 건물 주차장이에요. 오른쪽 두 번째 경사로로 들어가세요. 세 번째 경사로는 다음 건물 주차장이에요.",
            confuserLine(e),
        )
    }

    @Test
    fun `뒤쪽 입구만 있으면 들어갈 입구부터`() {
        val e = entrance.copy(orderFromApproach = 1, confusers = listOf(after))
        assertEquals("오른쪽 첫 번째 경사로로 들어가세요. 세 번째 경사로는 다음 건물 주차장이에요.", confuserLine(e))
    }

    @Test
    fun `미리 보기 음성에 운영 상태를 넣는다`() {
        val event = GuidanceEvent.SpeakPreview(Facility("f1", "시험 상가", "", listOf(entrance)), entrance)
        val building = OpeningHours(listOf(HoursRule.parse("매일", "07:00", "22:00", "PAID")), AccessStatus.CLOSED)
        val publicLot = OpeningHours(listOf(HoursRule.parse("월-금", "09:00", "19:00", "PAID")), AccessStatus.FREE)
        fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 11, h, m) // 수요일
        val base = "시험 상가 주차장 입구를 알려 드릴게요. 오른쪽 두 번째 경사로예요."

        assertEquals(base, SpeechTexts.preview(event))                                  // 시간표가 없는 입구
        assertEquals(base, SpeechTexts.preview(event, building.at(at(14)), at(14)))     // 평소에는 덧붙이지 않는다
        assertEquals("$base 22시에 문을 닫아요.", SpeechTexts.preview(event, building.at(at(21, 30)), at(21, 30)))
        assertEquals(
            "주의하세요. 시험 상가 주차장은 지금 운영 시간이 아니라 입구가 닫혀 있어요. 오른쪽 두 번째 경사로예요.",
            SpeechTexts.preview(event, building.at(at(23)), at(23)),
        )
        assertEquals("$base 지금은 무료로 열려 있어요.", SpeechTexts.preview(event, publicLot.at(at(20)), at(20)))
        // 무료 개방 중이어도 곧 닫으면 닫는 시각을 알린다 (차가 갇힐 수 있다)
        val freeUntil23 = OpeningHours(listOf(HoursRule.parse("매일", "06:00", "23:00", "FREE")), AccessStatus.CLOSED)
        assertEquals("$base 지금은 무료로 열려 있어요. 23시에 문을 닫아요.", SpeechTexts.preview(event, freeUntil23.at(at(22)), at(22)))
        assertEquals("지금은 무료 개방 · 23:00에 닫아요", SpeechTexts.accessLine(freeUntil23.at(at(22)), at(22)))
    }

    @Test
    fun `이름이 주차장으로 끝나면 주차장을 두 번 말하지 않는다`() {
        assertEquals("시험 상가 주차장", SpeechTexts.lotName("시험 상가"))
        assertEquals("시험 공영 주차장", SpeechTexts.lotName("시험 공영 주차장"))
        val event = GuidanceEvent.SpeakPreview(Facility("f1", "시험 공영 주차장", "", listOf(entrance)), entrance)
        assertEquals("시험 공영 주차장 입구를 알려 드릴게요. 오른쪽 두 번째 경사로예요.", SpeechTexts.preview(event))
    }

    @Test
    fun `입구 앞에서 들어갈 수 없으면 들어가라고 하지 않는다`() {
        val e = entrance.copy(confusers = listOf(before))
        val event = GuidanceEvent.SpeakConfuserWarning(e)
        assertEquals(SpeechTexts.confuser(event), SpeechTexts.atEntrance(event, "시험 상가", tooTallLimitM = null, closed = false))
        assertEquals("주의하세요. 높이 제한이 2.3미터라 이 차는 들어가기 어려워요.", SpeechTexts.atEntrance(event, "시험 상가", tooTallLimitM = 2.3, closed = false))
        assertEquals("주의하세요. 시험 상가 주차장은 지금 문이 닫혀 있어요.", SpeechTexts.atEntrance(event, "시험 상가", tooTallLimitM = null, closed = true))
    }

    @Test
    fun `반대쪽에서 오면 좌우와 순서를 말하지 않는다`() {
        val e = entrance.copy(confusers = listOf(before, after))
        val event = GuidanceEvent.SpeakPreview(Facility("f1", "시험 상가", "", listOf(e)), e, otherDirection = true)
        assertEquals("시험 상가 주차장 입구를 알려 드릴게요. ${SpeechTexts.OTHER_DIRECTION}", SpeechTexts.preview(event))
        assertEquals(
            "근처에 다른 건물 주차장 입구가 있어요. 간판을 확인하고 들어가세요.",
            SpeechTexts.confuser(GuidanceEvent.SpeakConfuserWarning(e, otherDirection = true)),
        )
        // 반대쪽에서 지나쳤으면 데이터의 "뒤쪽 입구"는 이미 지나온 쪽이라 경고하지 않는다
        assertEquals("입구를 지나쳤어요. 내비가 다시 안내할 거예요.", SpeechTexts.passed(e, otherDirection = true))
        listOf(SpeechTexts.preview(event), SpeechTexts.confuser(GuidanceEvent.SpeakConfuserWarning(e, true))).forEach {
            assertTrue(it, "오른쪽" !in it && "번째" !in it)
        }
    }

    @Test
    fun `지나쳤을 때 뒤쪽 입구가 있으면 들어가지 말라고 한다`() {
        assertEquals("입구를 지나쳤어요. 내비가 다시 안내할 거예요.", SpeechTexts.passed(entrance))
        assertEquals(
            "입구를 지나쳤어요. 바로 다음 경사로는 다른 건물 주차장이니 들어가지 마세요. 내비가 다시 안내할 거예요.",
            SpeechTexts.passed(entrance.copy(confusers = listOf(before, after))),
        )
    }
}
