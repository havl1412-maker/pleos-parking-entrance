package com.lastmeter.parking.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay

/**
 * 지금 이 입구로 들어갈 수 있는지.
 * 운영 시간이 끝난 주차장이 다 같지 않다: 공영 주차장은 무료로 열어 두는 곳이 많고, 건물 주차장은 셔터를 내려 아예 막는 곳이 많다.
 */
enum class AccessStatus {
    /** 열려 있고 요금을 받는다 */
    PAID,
    /** 열려 있고 요금을 받지 않는다 (공영 주차장의 야간·일요일 무료 개방 등) */
    FREE,
    /** 문을 닫아서 들어갈 수 없다 */
    CLOSED,
}

/** 공휴일 목록. 해마다 같은 날("MM-DD")과 그해에만 해당하는 날("YYYY-MM-DD", 설·추석·대체 공휴일) */
data class Holidays(val everyYear: Set<MonthDay> = emptySet(), val dates: Set<LocalDate> = emptySet()) {
    fun contains(date: LocalDate): Boolean = date in dates || MonthDay.from(date) in everyYear

    companion object {
        val NONE = Holidays()

        fun parse(items: List<String>): Holidays {
            val every = mutableSetOf<MonthDay>()
            val dates = mutableSetOf<LocalDate>()
            items.map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                if (it.length == 5) every += MonthDay.parse("--$it") else dates += LocalDate.parse(it)
            }
            return Holidays(every, dates)
        }
    }
}

/**
 * 요일·시간 구간 하나와 그때의 상태. 분 단위(0~1440).
 * from 이 to 보다 크면 자정을 넘는 구간이다(예: 22:00~06:00 은 그 요일 밤 10시부터 다음 날 아침 6시까지).
 */
data class HoursRule(
    val days: Set<DayOfWeek>,
    /** 공휴일에 적용하는 규칙인지 */
    val holiday: Boolean,
    val fromMin: Int,
    val toMin: Int,
    val status: AccessStatus,
) {
    companion object {
        private const val DAY_CHARS = "월화수목금토일"

        /** 데이터 파일의 글자 그대로 받는다. 예: parse("월-금", "09:00", "19:00", "PAID") */
        fun parse(days: String, from: String, to: String, status: String): HoursRule {
            val (set, holiday) = parseDays(days)
            return HoursRule(set, holiday, parseMinute(from), parseMinute(to), AccessStatus.valueOf(status.trim().uppercase()))
        }

        /** "월-금", "토,일", "금-월"(주말을 낀 범위), "매일", "평일", "주말", "일,공휴일" */
        fun parseDays(text: String): Pair<Set<DayOfWeek>, Boolean> {
            val days = mutableSetOf<DayOfWeek>()
            var holiday = false
            text.split(',', ' ', '·').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
                when {
                    token == "매일" -> days += DayOfWeek.values()
                    token == "평일" -> days += (1..5).map { DayOfWeek.of(it) }
                    token == "주말" -> days += listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                    token == "공휴일" -> holiday = true
                    '-' in token || '~' in token -> {
                        val (a, b) = token.split('-', '~').map { dayOf(it.trim()) }
                        var d = a
                        while (true) {
                            days += d
                            if (d == b) break
                            d = d.plus(1)
                        }
                    }
                    else -> days += dayOf(token)
                }
            }
            require(days.isNotEmpty() || holiday) { "요일을 알 수 없어요: $text" }
            return days to holiday
        }

        private fun dayOf(token: String): DayOfWeek {
            val i = DAY_CHARS.indexOf(token.firstOrNull() ?: ' ')
            require(i >= 0 && (token.length == 1 || token == "${DAY_CHARS[i]}요일")) { "요일을 알 수 없어요: $token" }
            return DayOfWeek.of(i + 1)
        }

        /** "09:00" → 540, "24:00" → 1440 */
        fun parseMinute(text: String): Int {
            val (h, m) = text.trim().split(':').map { it.toInt() }
            require(h in 0..24 && m in 0..59 && h * 60 + m <= 1440) { "시각을 알 수 없어요: $text" }
            return h * 60 + m
        }
    }
}

/** 어느 시각의 상태와, 앞으로 언제 바뀌는지 */
data class Access(
    val status: AccessStatus,
    /** 상태가 다음에 바뀌는 시각과 그때의 상태. 일주일 넘게 안 바뀌면 null */
    val changesAt: LocalDateTime?,
    val nextStatus: AccessStatus?,
    /** 지금 열려 있을 때, 다음에 문을 닫는 시각. 주차한 차가 갇히지 않게 알려 주는 데 쓴다 */
    val closesAt: LocalDateTime?,
)

/**
 * 입구 하나의 운영 시간표. 위에서부터 처음 맞는 규칙의 상태를 쓰고, 맞는 규칙이 없으면 otherwise 다.
 * 공휴일 규칙("공휴일")이 하나라도 있으면 공휴일에는 요일 규칙 대신 공휴일 규칙만 본다.
 * 안드로이드에 의존하지 않는 순수 코틀린이라 단위 테스트로 검증한다.
 */
data class OpeningHours(
    val rules: List<HoursRule>,
    val otherwise: AccessStatus,
    val holidays: Holidays = Holidays.NONE,
) {
    private val hasHolidayRule = rules.any { it.holiday }

    private fun appliesOn(rule: HoursRule, date: LocalDate): Boolean =
        if (hasHolidayRule && holidays.contains(date)) rule.holiday else date.dayOfWeek in rule.days

    fun statusAt(time: LocalDateTime): AccessStatus {
        val minute = time.hour * 60 + time.minute
        val today = time.toLocalDate()
        val rule = rules.firstOrNull { r ->
            if (r.fromMin < r.toMin) {
                appliesOn(r, today) && minute >= r.fromMin && minute < r.toMin
            } else {
                // 자정을 넘는 구간: 오늘 시작한 앞부분이거나, 어제 시작한 뒷부분
                (appliesOn(r, today) && minute >= r.fromMin) || (appliesOn(r, today.minusDays(1)) && minute < r.toMin)
            }
        }
        return rule?.status ?: otherwise
    }

    fun at(time: LocalDateTime): Access {
        val now = statusAt(time)
        // 상태가 바뀔 수 있는 시각은 규칙의 시작·끝과 자정뿐이다. 앞으로 8일 치만 본다
        val minutes = (rules.flatMap { listOf(it.fromMin, it.toMin) } + 0).distinct()
        val boundaries = (0L..8L).flatMap { d ->
            val midnight = time.toLocalDate().plusDays(d).atStartOfDay()
            minutes.map { midnight.plusMinutes(it.toLong()) }
        }.filter { it.isAfter(time) }.distinct().sorted()
        val changesAt = boundaries.firstOrNull { statusAt(it) != now }
        val closesAt = if (now == AccessStatus.CLOSED) null else boundaries.firstOrNull { statusAt(it) == AccessStatus.CLOSED }
        return Access(now, changesAt, changesAt?.let { statusAt(it) }, closesAt)
    }
}
