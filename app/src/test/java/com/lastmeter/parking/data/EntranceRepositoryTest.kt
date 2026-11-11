package com.lastmeter.parking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EntranceRepositoryTest {

    // 같은 키를 두 번 넣으면 org.json 이 파일 전체를 거부하므로, 바꿀 칸은 인자로 받는다
    private fun entrance(id: String, kind: String = "IN", roadSide: String = "RIGHT", extra: String = "") = """
        {"id": "$id", "kind": "$kind", "lat": 37.4145, "lng": 127.0947, "approachBearingDeg": 0,
         "roadSide": "$roadSide", "orderFromApproach": 2, "landmark": "편의점 다음", "speech": "오른쪽이에요." $extra}
    """

    private fun file(vararg facilities: String) = """{"version": 1, "facilities": [${facilities.joinToString(",")}]}"""

    private fun facility(id: String, vararg entrances: String) =
        """{"id": "$id", "name": "$id 상가", "address": "", "entrances": [${entrances.joinToString(",")}]}"""

    @Test
    fun `저장소의 실제 데이터 파일은 문제 없이 읽힌다`() {
        // 단위 테스트는 app 폴더에서 돈다
        val data = EntranceRepository.parse(File("src/main/assets/entrances.json").readText())
        assertEquals(emptyList<String>(), data.problems)
        assertTrue(data.facilities.isNotEmpty())
    }

    @Test
    fun `입구 하나가 틀려도 나머지는 읽고 무엇이 틀렸는지 알려 준다`() {
        val data = EntranceRepository.parse(
            file(
                facility("a", entrance("a-1"), entrance("a-2", roadSide = "RIHGT")),
                facility("b", entrance("b-1")),
            )
        )
        assertEquals(listOf("a", "b"), data.facilities.map { it.id })
        assertEquals(listOf("a-1"), data.facilities[0].entrances.map { it.id })
        assertEquals(1, data.problems.size)
        assertTrue(data.problems[0], "a 의 입구 2번째" in data.problems[0] && "RIHGT" in data.problems[0] && "LEFT/RIGHT" in data.problems[0])
    }

    @Test
    fun `입구가 다 틀린 시설은 빼고 알려 준다`() {
        val data = EntranceRepository.parse(file(facility("a", entrance("a-1", kind = "ENTER")), facility("b", entrance("b-1"))))
        assertEquals(listOf("b"), data.facilities.map { it.id })
        assertTrue(data.problems.any { "a: 읽을 수 있는 입구가 없어서" in it })
    }

    @Test
    fun `운영 시간표가 틀리면 시간표만 빼고 입구는 안내한다`() {
        val bad = """, "schedule": {"rules": [{"days": "월~금요", "from": "9시", "to": "19:00", "status": "PAID"}], "otherwise": "FREE"} """
        val data = EntranceRepository.parse(file(facility("a", entrance("a-1", extra = bad))))
        val e = data.facilities.single().entrances.single()
        assertNull(e.schedule)
        assertTrue(data.problems.single(), "운영 시간표를 뺐어요" in data.problems.single())
    }

    @Test
    fun `위도와 경도를 바꿔 적으면 알려 준다`() {
        val swapped = """{"id": "a-1", "lat": 127.0947, "lng": 37.4145}"""
        val data = EntranceRepository.parse(file(facility("a", swapped)))
        assertTrue(data.problems.any { "위도·경도 순서" in it })
    }

    @Test
    fun `JSON 형식이 깨지면 빈 목록과 이유를 돌려준다`() {
        val data = EntranceRepository.parse("""{"facilities": [ {"id": "a", } ]""")
        assertEquals(emptyList<Facility>(), data.facilities)
        assertTrue(data.problems.single(), "JSON 형식이 틀렸어요" in data.problems.single())
    }

    // 같은 칸을 두 번 적은 경우는 시험하지 않는다. PC 의 org.json 은 오류로 보지만 안드로이드의 org.json 은 뒤의 값을 조용히 쓴다

    @Test
    fun `공휴일 하나가 틀려도 나머지 공휴일은 읽는다`() {
        val sched = """, "schedule": {"rules": [{"days": "공휴일", "from": "00:00", "to": "24:00", "status": "FREE"}], "otherwise": "PAID"} """
        val json = """{"holidays": ["12-25", "12월 31일"], "facilities": [${facility("a", entrance("a-1", extra = sched))}]}"""
        val data = EntranceRepository.parse(json)
        assertTrue(data.problems.single(), "12월 31일" in data.problems.single())
        val s = data.facilities.single().entrances.single().schedule!!
        assertEquals(AccessStatus.FREE, s.statusAt(java.time.LocalDateTime.of(2026, 12, 25, 14, 0)))
    }

    @Test
    fun `나가는 출구만 있는 시설은 알려 준다`() {
        val data = EntranceRepository.parse(file(facility("a", entrance("a-1", kind = "OUT"))))
        assertTrue(data.problems.any { "들어가는 입구(IN/BOTH)가 없어서" in it })
    }

    @Test
    fun `id 가 겹치면 알려 준다`() {
        val data = EntranceRepository.parse(file(facility("a", entrance("x")), facility("b", entrance("x"))))
        assertTrue(data.problems.any { "입구 id 가 겹쳐요: x" in it })
    }

    @Test
    fun `대소문자와 공백은 봐준다`() {
        val data = EntranceRepository.parse(file(facility("a", entrance("a-1", roadSide = " left "))))
        assertEquals(RoadSide.LEFT, data.facilities.single().entrances.single().roadSide)
        assertEquals(emptyList<String>(), data.problems)
    }
}
