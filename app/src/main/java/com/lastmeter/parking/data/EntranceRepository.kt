package com.lastmeter.parking.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** assets/entrances.json 에서 입구 데이터를 읽는다. 형식과 수집 방법: docs/data-collection.md */
class EntranceRepository(private val context: Context) {

    fun load(): List<Facility> {
        val text = context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(text)
    }

    companion object {
        const val FILE_NAME = "entrances.json"

        fun parse(json: String): List<Facility> {
            val root = JSONObject(json)
            return root.getJSONArray("facilities").mapObjects { f ->
                Facility(
                    id = f.getString("id"),
                    name = f.getString("name"),
                    address = f.optString("address"),
                    entrances = f.getJSONArray("entrances").mapObjects { e ->
                        Entrance(
                            id = e.getString("id"),
                            kind = EntranceKind.valueOf(e.optString("kind", "BOTH")),
                            lat = e.getDouble("lat"),
                            lng = e.getDouble("lng"),
                            approachBearingDeg = e.optDouble("approachBearingDeg", 0.0),
                            roadSide = RoadSide.valueOf(e.optString("roadSide", "RIGHT")),
                            orderFromApproach = e.optInt("orderFromApproach", 1),
                            landmark = e.optString("landmark"),
                            speech = e.optString("speech"),
                            heightLimitM = e.optNullableDouble("heightLimitM"),
                            feeText = e.optNullableString("feeText"),
                            hoursText = e.optNullableString("hoursText"),
                            photoFar = e.optNullableString("photoFar"),
                            photoNear = e.optNullableString("photoNear"),
                            confusers = (e.optJSONArray("confusers") ?: JSONArray()).mapObjects { c ->
                                Confuser(
                                    description = c.getString("description"),
                                    lat = c.getDouble("lat"),
                                    lng = c.getDouble("lng"),
                                )
                            },
                        )
                    },
                )
            }
        }

        private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
            (0 until length()).map { transform(getJSONObject(it)) }

        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key) || !has(key)) null else getString(key).ifBlank { null }

        private fun JSONObject.optNullableDouble(key: String): Double? =
            if (isNull(key) || !has(key)) null else getDouble(key)
    }
}
