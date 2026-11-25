package dev.hydranet.opensteves.data

import org.json.JSONObject

data class Playlist(
    val id: String,
    val title: String,
    val subtitle: String,
    val stops: List<PlaylistStop>,
) {
    companion object {
        fun fromJson(json: JSONObject): Playlist {
            val stops = json.getJSONArray("stops")
            return Playlist(
                id = json.getString("id"),
                title = json.getString("title"),
                subtitle = json.optString("subtitle"),
                stops = List(stops.length()) { PlaylistStop.fromJson(stops.getJSONObject(it)) },
            )
        }

        fun parse(text: String): Playlist = fromJson(JSONObject(text))
    }
}

data class PlaylistStop(
    val id: String,
    val dayLabel: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val guideId: String? = null,
    val officialMatch: String? = null,
    val note: String? = null,
) {
    val isCustom: Boolean get() = kind == KIND_CUSTOM

    companion object {
        const val KIND_CUSTOM = "custom"
        const val KIND_OFFICIAL = "official"

        fun fromJson(json: JSONObject): PlaylistStop = PlaylistStop(
            id = json.getString("id"),
            dayLabel = json.optString("dayLabel"),
            kind = json.getString("kind"),
            title = json.getString("title"),
            subtitle = json.optString("subtitle"),
            guideId = json.optString("guideId").ifBlank { null },
            officialMatch = json.optString("officialMatch").ifBlank { null },
            note = json.optString("note").ifBlank { null },
        )
    }
}

data class GuideLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

data class GuideSubchapter(
    val title: String,
    val text: String,
    val image: String,
    val caption: String,
    val directions: String,
) {
    companion object {
        fun fromJson(json: JSONObject): GuideSubchapter = GuideSubchapter(
            title = json.optString("title"),
            text = json.optString("text"),
            image = json.optString("image"),
            caption = json.optString("caption"),
            directions = json.optString("directions"),
        )
    }
}

data class GuideChapter(
    val title: String,
    val image: String,
    val caption: String,
    val intro: String,
    val subchapters: List<GuideSubchapter>,
) {
    companion object {
        fun fromJson(json: JSONObject): GuideChapter {
            val subchapters = json.optJSONArray("subchapters")
            return when {
                subchapters != null -> GuideChapter(
                    title = json.optString("title"),
                    image = json.optString("image"),
                    caption = json.optString("caption"),
                    intro = json.optString("intro"),
                    subchapters = List(subchapters.length()) { GuideSubchapter.fromJson(subchapters.getJSONObject(it)) },
                )
                // Legacy chapters carried their narration in a single chapter-level
                // "text" field; map that onto one untitled subchapter so old guide
                // files keep parsing and playing exactly as before.
                json.has("text") -> GuideChapter(
                    title = json.optString("title"),
                    image = json.optString("image"),
                    caption = json.optString("caption"),
                    intro = "",
                    subchapters = listOf(
                        GuideSubchapter(
                            title = "",
                            text = json.optString("text"),
                            image = "",
                            caption = "",
                            directions = json.optString("directions"),
                        ),
                    ),
                )
                else -> GuideChapter(
                    title = json.optString("title"),
                    image = json.optString("image"),
                    caption = json.optString("caption"),
                    intro = json.optString("intro"),
                    subchapters = emptyList(),
                )
            }
        }
    }
}

data class CustomGuide(
    val id: String,
    val title: String,
    val subtitle: String,
    val area: String,
    val dayLabel: String,
    val estimatedMinutes: Int,
    val location: GuideLocation?,
    val heroImage: String,
    val intro: String,
    val practical: List<String>,
    val mapNote: String,
    val chapters: List<GuideChapter>,
    val sources: List<String>,
) {
    companion object {
        fun fromJson(json: JSONObject): CustomGuide {
            val location = json.optJSONObject("location")?.let {
                GuideLocation(it.optString("name"), it.optDouble("latitude", 0.0), it.optDouble("longitude", 0.0))
            }
            val practical = json.optJSONArray("practical")
            val chapters = json.optJSONArray("chapters")
            val sources = json.optJSONArray("sources")
            return CustomGuide(
                id = json.getString("id"),
                title = json.getString("title"),
                subtitle = json.optString("subtitle"),
                area = json.optString("area"),
                dayLabel = json.optString("dayLabel"),
                estimatedMinutes = json.optInt("estimatedMinutes"),
                location = location,
                heroImage = json.optString("heroImage"),
                intro = json.optString("intro"),
                practical = if (practical == null) emptyList() else List(practical.length()) { practical.optString(it) },
                mapNote = json.optString("mapNote"),
                chapters = if (chapters == null) emptyList() else List(chapters.length()) { index ->
                    GuideChapter.fromJson(chapters.getJSONObject(index))
                },
                sources = if (sources == null) emptyList() else List(sources.length()) { sources.optString(it) },
            )
        }

        fun parse(text: String): CustomGuide = fromJson(JSONObject(text))
    }
}
