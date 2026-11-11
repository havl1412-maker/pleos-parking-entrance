package com.lastmeter.parking.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 읽은 입구 데이터와, 읽다가 발견한 문제들(사람이 고쳐야 할 것) */
data class EntranceData(val facilities: List<Facility>, val problems: List<String>)

/**
 * assets/entrances.json 에서 입구 데이터를 읽는다. 형식과 수집 방법: docs/data-collection.md
 *
 * 팀원 여러 명이 손으로 고치는 파일이라 틀린 곳이 생기기 쉽다. 그래서 한 곳이 틀려도 전체를 버리지 않는다.
 * 틀린 입구·시설만 빼고 나머지는 읽고, 무엇이 왜 빠졌는지 problems 에 모아 대기 화면과 로그에 보여 준다.
 */
class EntranceRepository(private val context: Context) {

    fun load(): EntranceData {
        val text = try {
            context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return EntranceData(emptyList(), listOf("$FILE_NAME 파일을 열 수 없어요: ${e.message}"))
        }
        val data = parse(text)
        // 사진 이름을 틀리게 적으면 화면에 "사진 준비 중"만 떠서 알아채기 어렵다
        val photos = runCatching { context.assets.list(PHOTO_DIR)?.toSet() }.getOrNull().orEmpty()
        val missing = data.facilities.flatMap { f ->
            f.entrances.flatMap { e ->
                listOfNotNull(e.photoFar, e.photoNear, e.photoFarNight, e.photoNearNight)
                    .filter { it !in photos }
                    .map { "${e.id}: 사진 파일이 없어요 ($PHOTO_DIR/$it)" }
            }
        }
        return data.copy(problems = data.problems + missing)
    }

    companion object {
        const val FILE_NAME = "entrances.json"
        const val PHOTO_DIR = "entrances"

        /** 안드로이드에 의존하지 않아 단위 테스트로 검증한다 (EntranceRepositoryTest) */
        fun parse(json: String): EntranceData {
            val problems = mutableListOf<String>()
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                return EntranceData(emptyList(), listOf("JSON 형식이 틀렸어요 (쉼표·따옴표·괄호 확인): ${e.message}"))
            }
            val holidays = root.optJSONArray("holidays")?.let { a ->
                val ok = (0 until a.length()).mapNotNull { i ->
                    val text = a.optString(i)
                    runCatching { Holidays.parse(listOf(text)); text }
                        .onFailure { problems += "holidays 의 \"$text\": 날짜를 읽을 수 없어요 (\"MM-DD\" 또는 \"YYYY-MM-DD\")" }
                        .getOrNull()
                }
                Holidays.parse(ok)
            } ?: Holidays.NONE
            val list = root.optJSONArray("facilities")
                ?: return EntranceData(emptyList(), problems + "facilities 목록이 없어요")

            val facilities = (0 until list.length()).mapNotNull { i ->
                runCatching { parseFacility(list.getJSONObject(i), holidays, problems) }
                    .onFailure { problems += "facilities ${i + 1}번째: ${it.message}" }
                    .getOrNull()
            }
            facilities.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { problems += "시설 id 가 겹쳐요: $it" }
            facilities.flatMap { it.entrances }.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { problems += "입구 id 가 겹쳐요: $it (피드백 기록이 섞여요)" }
            return EntranceData(facilities, problems)
        }

        private fun parseFacility(f: JSONObject, holidays: Holidays, problems: MutableList<String>): Facility? {
            val id = f.getString("id")
            val array = f.getJSONArray("entrances")
            val entrances = (0 until array.length()).mapNotNull { j ->
                runCatching { parseEntrance(array.getJSONObject(j), holidays, problems) }
                    .onFailure { problems += "$id 의 입구 ${j + 1}번째: ${it.message}" }
                    .getOrNull()
            }
            if (entrances.isEmpty()) {
                problems += "$id: 읽을 수 있는 입구가 없어서 시설을 뺐어요"
                return null
            }
            if (entrances.none { it.canEnter }) problems += "$id: 들어가는 입구(IN/BOTH)가 없어서 안내하지 않아요 (OUT 만 있음)"
            val naviLat = f.optNullableDouble("naviLat")
            val naviLng = f.optNullableDouble("naviLng")
            val naviOk = naviLat != null && naviLng != null && naviLat in 33.0..39.0 && naviLng in 124.0..132.0
            if ((naviLat != null || naviLng != null) && !naviOk) problems += "$id: naviLat/naviLng 가 이상해서 뺐어요 ($naviLat, $naviLng)"
            return Facility(
                id = id, name = f.getString("name"), address = f.optString("address"), entrances = entrances,
                naviLat = naviLat.takeIf { naviOk }, naviLng = naviLng.takeIf { naviOk },
            )
        }

        private fun parseEntrance(e: JSONObject, holidays: Holidays, problems: MutableList<String>): Entrance {
            val id = e.getString("id")
            val lat = e.getDouble("lat")
            val lng = e.getDouble("lng")
            require(lat in 33.0..39.0 && lng in 124.0..132.0) { "좌표가 국내 범위가 아니에요 ($lat, $lng). 위도·경도 순서를 확인하세요" }
            val bearing = e.optDouble("approachBearingDeg", 0.0)
            require(bearing in 0.0..360.0) { "approachBearingDeg 는 0~360 이어야 해요 ($bearing)" }
            val order = e.optInt("orderFromApproach", 1)
            require(order >= 1) { "orderFromApproach 는 1 이상이어야 해요 ($order)" }
            val height = e.optNullableDouble("heightLimitM")
            require(height == null || height in 1.0..5.0) { "heightLimitM 이 이상해요 ($height m)" }

            val confusers = (e.optJSONArray("confusers") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { k ->
                    runCatching {
                        val c = a.getJSONObject(k)
                        Confuser(
                            description = c.getString("description"),
                            lat = c.getDouble("lat"),
                            lng = c.getDouble("lng"),
                            after = c.optBoolean("after", false),
                        )
                    }.onFailure { problems += "$id 의 헷갈리는 입구 ${k + 1}번째를 뺐어요: ${it.message}" }.getOrNull()
                }
            }
            val schedule = e.optJSONObject("schedule")?.let { s ->
                runCatching { parseSchedule(s, holidays) }
                    .onFailure { problems += "$id 의 운영 시간표를 뺐어요 (운영 시간 안내 없이 안내함): ${it.message}" }
                    .getOrNull()
            }
            return Entrance(
                id = id,
                kind = enumOf(e.optString("kind", "BOTH"), "kind"),
                lat = lat,
                lng = lng,
                approachBearingDeg = bearing,
                roadSide = enumOf(e.optString("roadSide", "RIGHT"), "roadSide"),
                orderFromApproach = order,
                landmark = e.optString("landmark"),
                speech = e.optString("speech"),
                heightLimitM = height,
                feeText = e.optNullableString("feeText"),
                hoursText = e.optNullableString("hoursText"),
                photoFar = e.optNullableString("photoFar"),
                photoNear = e.optNullableString("photoNear"),
                confusers = confusers,
                photoFarNight = e.optNullableString("photoFarNight"),
                photoNearNight = e.optNullableString("photoNearNight"),
                schedule = schedule,
            )
        }

        private fun parseSchedule(s: JSONObject, holidays: Holidays): OpeningHours {
            val rules = s.optJSONArray("rules") ?: JSONArray()
            return OpeningHours(
                rules = (0 until rules.length()).map { i ->
                    val r = rules.getJSONObject(i)
                    HoursRule.parse(r.getString("days"), r.getString("from"), r.getString("to"), r.getString("status"))
                },
                otherwise = enumOf(s.optString("otherwise", "CLOSED"), "otherwise"),
                holidays = holidays,
            )
        }

        /** 대소문자·앞뒤 공백은 봐주고, 모르는 값이면 쓸 수 있는 값을 알려 준다 */
        private inline fun <reified T : Enum<T>> enumOf(value: String, field: String): T {
            val v = value.trim().uppercase()
            return enumValues<T>().firstOrNull { it.name == v }
                ?: throw IllegalArgumentException("$field 값 \"$value\" 을 알 수 없어요 (${enumValues<T>().joinToString("/")})")
        }

        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key) || !has(key)) null else getString(key).ifBlank { null }

        private fun JSONObject.optNullableDouble(key: String): Double? =
            if (isNull(key) || !has(key)) null else getDouble(key)
    }
}
