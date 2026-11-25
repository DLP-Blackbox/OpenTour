package dev.hydranet.opensteves.data

import org.json.JSONObject

data class WikipediaArticle(
    val guideId: String,
    val title: String,
    val sourceUrl: String,
    val retrieved: String,
    val text: String,
) {
    companion object {
        fun fromJson(json: JSONObject): WikipediaArticle = WikipediaArticle(
            guideId = json.getString("guideId"),
            title = json.getString("title"),
            sourceUrl = json.optString("sourceUrl"),
            retrieved = json.optString("retrieved"),
            text = json.optString("text"),
        )

        fun parse(text: String): WikipediaArticle = fromJson(JSONObject(text))
    }
}
