package dev.hydranet.opensteves

import dev.hydranet.opensteves.data.CustomGuide
import dev.hydranet.opensteves.data.GuideChapter
import dev.hydranet.opensteves.data.GuideSubchapter
import dev.hydranet.opensteves.data.Playlist
import dev.hydranet.opensteves.data.PlaylistStop
import dev.hydranet.opensteves.data.SearchIndex
import dev.hydranet.opensteves.data.SearchResultKind
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.data.WikipediaArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private val index = SearchIndex.build(
        tours = listOf(TOUR),
        playlists = listOf(PLAYLIST),
        guides = listOf(GUIDE),
        wikipedia = listOf(ARTICLE),
    )

    @Test
    fun blankAndShortQueriesMatchNothing() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("t").isEmpty())
    }

    @Test
    fun titleMatchesAcrossKinds() {
        val results = index.search("tower")
        val kinds = results.map { it.kind }
        assertTrue(SearchResultKind.TOUR in kinds)
        assertTrue(SearchResultKind.STOP in kinds)
        assertTrue(SearchResultKind.GUIDE in kinds)
        assertTrue(SearchResultKind.WIKIPEDIA in kinds)
        assertEquals(1, results.first { it.kind == SearchResultKind.TOUR }.tourId)
    }

    @Test
    fun bodyTextMatchFindsChapterWithSnippet() {
        val results = index.search("Cullinan")
        val chapter = results.single { it.kind == SearchResultKind.CHAPTER }
        assertEquals("tower-of-london", chapter.guideId)
        assertEquals(0, chapter.chapterIndex)
        assertTrue(chapter.snippet.contains("Cullinan"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val results = index.search("CULLINAN")
        assertTrue(results.any { it.kind == SearchResultKind.CHAPTER && it.chapterIndex == 0 })
    }

    @Test
    fun subchapterTextMatchLandsOnItsChapter() {
        val results = index.search("Norman chapel")
        val chapter = results.single { it.kind == SearchResultKind.CHAPTER }
        assertEquals(1, chapter.chapterIndex)
    }

    @Test
    fun wikipediaBodyMatchCarriesGuideId() {
        val results = index.search("historic castle")
        val article = results.single { it.kind == SearchResultKind.WIKIPEDIA }
        assertEquals("tower-of-london", article.guideId)
        assertTrue(article.snippet.contains("historic castle"))
    }

    @Test
    fun unknownTermMatchesNothing() {
        assertTrue(index.search("zzzznotpresent").isEmpty())
    }

    private companion object {
        val TOUR = Tour(
            1, "Rick Steves — Tower Walk", "London", "Walking tour",
            "A walk past the Tower.", "", "tower-walk", 1, 1_000L,
        )
        val GUIDE = CustomGuide(
            id = "tower-of-london",
            title = "Tower of London",
            subtitle = "",
            area = "London",
            dayLabel = "Wednesday",
            estimatedMinutes = 60,
            location = null,
            heroImage = "",
            intro = "A fortress on the Thames.",
            practical = emptyList(),
            mapNote = "",
            chapters = listOf(
                GuideChapter(
                    "The Crown Jewels", "", "", "Guarded since 1661.",
                    listOf(GuideSubchapter("The Imperial State Crown", "It holds the Cullinan II diamond.", "", "", "")),
                ),
                GuideChapter(
                    "The White Tower", "", "", "",
                    listOf(GuideSubchapter("Chapel of St John", "A Norman chapel in pale stone.", "", "", "")),
                ),
            ),
            sources = emptyList(),
        )
        val PLAYLIST = Playlist(
            "london", "London", "",
            listOf(PlaylistStop("tower", "Wednesday", PlaylistStop.KIND_CUSTOM, "Tower of London", "", guideId = "tower-of-london")),
        )
        val ARTICLE = WikipediaArticle(
            "tower-of-london", "Tower of London", "https://en.wikipedia.org/wiki/Tower_of_London",
            "2026-11-24", "The Tower of London is a historic castle founded in 1066.",
        )
    }
}
