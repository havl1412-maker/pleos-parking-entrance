package com.lastmeter.parking.data

import com.lastmeter.parking.guidance.SpeechTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class OpeningHoursTest {

    // 2026-11-11 수요일, 11-14 토요일, 11-15 일요일
    private fun wed(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 11, h, m)
    private fun sat(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 14, h, m)
    private fun sun(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 15, h, m)

    /** 공영 주차장: 평일 09~19시·토 09~15시 유료, 그 외와 공휴일은 무료 개방 */
    private val publicLot = OpeningHours(
        rules = listOf(
            HoursRule.parse("공휴일", "00:00", "24:00", "FREE"),
            HoursRule.parse("월-금", "09:00", "19:00", "PAID"),
            HoursRule.parse("토", "09:00", "15:00", "PAID"),
        ),
        otherwise = AccessStatus.FREE,
        holidays = Holidays.parse(listOf("12-25", "2027-02-08")),
    )

    /** 건물 주차장: 매일 07~22시, 밤에는 셔터를 내린다 */
    private val building = OpeningHours(listOf(HoursRule.parse("매일", "07:00", "22:00", "PAID")), AccessStatus.CLOSED)

    @Test
    fun `요일 글자를 읽는다`() {
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), HoursRule.parseDays("월-금").first)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), HoursRule.parseDays("토,일").first)
        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY), HoursRule.parseDays("금-월").first)
        assertEquals(7, HoursRule.parseDays("매일").first.size)
        assertEquals(setOf(DayOfWeek.SUNDAY) to true, HoursRule.parseDays("일,공휴일"))
        assertEquals(1440, HoursRule.parseMinute("24:00"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `모르는 요일은 틀렸다고 알린다`() {
        HoursRule.parseDays("웕")
    }

    @Test
    fun `공영 주차장은 운영 시간이 지나면 닫는 게 아니라 무료 개방`() {
        assertEquals(AccessStatus.PAID, publicLot.statusAt(wed(14)))
        assertEquals(AccessStatus.FREE, publicLot.statusAt(wed(19)))  // 19:00 정각부터 무료
        assertEquals(AccessStatus.FREE, publicLot.statusAt(wed(8, 59)))
        assertEquals(AccessStatus.PAID, publicLot.statusAt(sat(14, 59)))
        assertEquals(AccessStatus.FREE, publicLot.statusAt(sat(15)))
        assertEquals(AccessStatus.FREE, publicLot.statusAt(sun(14)))
    }

    @Test
    fun `공휴일에는 평일이어도 공휴일 규칙을 따른다`() {
        assertEquals(AccessStatus.FREE, publicLot.statusAt(LocalDateTime.of(2026, 12, 25, 14, 0))) // 금요일 성탄절
        assertEquals(AccessStatus.FREE, publicLot.statusAt(LocalDateTime.of(2027, 2, 8, 14, 0)))   // 월요일, 그해에만 넣은 날
        assertEquals(AccessStatus.PAID, publicLot.statusAt(LocalDateTime.of(2026, 12, 24, 14, 0)))
    }

    @Test
    fun `건물 주차장은 밤에 문을 닫고 다음 날 아침에 연다`() {
        val night = building.at(wed(23))
        assertEquals(AccessStatus.CLOSED, night.status)
        assertEquals(LocalDateTime.of(2026, 11, 12, 7, 0), night.changesAt)
        assertEquals(AccessStatus.PAID, night.nextStatus)
        assertNull(night.closesAt)
        assertEquals("지금은 문을 닫았어요 · 내일 07:00에 열어요", SpeechTexts.accessLine(night, wed(23)))
        // 자정을 넘긴 새벽에는 "내일"이 아니라 오늘 아침이다
        val dawn = LocalDateTime.of(2026, 11, 12, 1, 0)
        assertEquals("지금은 문을 닫았어요 · 07:00에 열어요", SpeechTexts.accessLine(building.at(dawn), dawn))
    }

    @Test
    fun `문 닫기 두 시간 전부터 닫는 시각을 알려 준다`() {
        assertNull(SpeechTexts.accessLine(building.at(wed(14)), wed(14)))          // 한참 남았으면 조용히
        assertEquals(wed(22), building.at(wed(14)).closesAt)                       // 그래도 닫는 시각은 알고 있다
        assertEquals("22:00에 문을 닫아요", SpeechTexts.accessLine(building.at(wed(21, 30)), wed(21, 30)))
        assertEquals("22시", SpeechTexts.clockSpoken(wed(22), wed(21, 30)))
    }

    @Test
    fun `무료 개방 중에는 언제부터 유료인지 알려 준다`() {
        assertEquals("지금은 무료 개방 · 내일 09:00부터 유료", SpeechTexts.accessLine(publicLot.at(wed(23)), wed(23)))
        assertEquals("지금은 무료 개방 · 월요일 09:00부터 유료", SpeechTexts.accessLine(publicLot.at(sat(16)), sat(16)))
        assertNull(publicLot.at(wed(23)).closesAt) // 닫는 일이 없는 주차장
    }

    @Test
    fun `자정을 넘는 구간`() {
        // 금·토 밤 10시부터 다음 날 새벽 2시까지만 여는 곳
        val lateNight = OpeningHours(listOf(HoursRule.parse("금,토", "22:00", "02:00", "PAID")), AccessStatus.CLOSED)
        assertEquals(AccessStatus.PAID, lateNight.statusAt(sat(23)))
        assertEquals(AccessStatus.PAID, lateNight.statusAt(sun(1)))    // 토요일 밤에 시작한 구간의 뒷부분
        assertEquals(AccessStatus.CLOSED, lateNight.statusAt(sun(2)))
        assertEquals(AccessStatus.CLOSED, lateNight.statusAt(sun(23))) // 일요일 밤은 규칙에 없다
        // 밤 12시에 닫는 곳은 "자정"
        val untilMidnight = OpeningHours(listOf(HoursRule.parse("매일", "06:00", "24:00", "PAID")), AccessStatus.CLOSED)
        assertEquals("자정에 문을 닫아요", SpeechTexts.accessLine(untilMidnight.at(wed(23)), wed(23)))
    }

    @Test
    fun `24시간 여는 곳은 바뀌는 시각이 없다`() {
        val always = OpeningHours(emptyList(), AccessStatus.PAID)
        val a = always.at(wed(3))
        assertEquals(AccessStatus.PAID, a.status)
        assertNull(a.changesAt)
        assertNull(SpeechTexts.accessLine(a, wed(3)))
    }
}
