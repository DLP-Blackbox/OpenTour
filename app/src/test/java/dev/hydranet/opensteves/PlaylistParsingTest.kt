package dev.hydranet.opensteves

import dev.hydranet.opensteves.data.CustomGuide
import dev.hydranet.opensteves.data.Playlist
import dev.hydranet.opensteves.data.PlaylistStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParsingTest {
    @Test
    fun playlistParsesStopsInOrderWithKinds() {
        val playlist = Playlist.parse(PLAYLIST_JSON)

        assertEquals("london-2026", playlist.id)
        assertEquals("London", playlist.title)
        assertEquals(3, playlist.stops.size)

        val tower = playlist.stops[0]
        assertEquals("tower-of-london", tower.id)
        assertEquals("Wednesday", tower.dayLabel)
        assertEquals(PlaylistStop.KIND_CUSTOM, tower.kind)
        assertTrue(tower.isCustom)
        assertEquals("tower-of-london", tower.guideId)
        assertNull(tower.officialMatch)
        assertNull(tower.note)

        val walk = playlist.stops[1]
        assertEquals(PlaylistStop.KIND_OFFICIAL, walk.kind)
        assertFalse(walk.isCustom)
        assertEquals("city of london", walk.officialMatch)
        assertNull(walk.guideId)

        val pauls = playlist.stops[2]
        assertEquals("paul", pauls.officialMatch)
        assertEquals("Exterior viewing only", pauls.note)
    }

    @Test
    fun guideParsesChaptersPracticalAndLocation() {
        val guide = CustomGuide.parse(GUIDE_JSON)

        assertEquals("tower-of-london", guide.id)
        assertEquals("Tower of London", guide.title)
        assertEquals("London", guide.area)
        assertEquals("Wednesday", guide.dayLabel)
        assertEquals(150, guide.estimatedMinutes)
        assertEquals("images/tower-of-london/hero.jpg", guide.heroImage)
        assertEquals(listOf("Arrive at opening time.", "Book tickets ahead."), guide.practical)
        assertEquals(2, guide.chapters.size)
        assertEquals("Welcome", guide.chapters[0].title)
        assertEquals("images/tower-of-london/01.jpg", guide.chapters[0].image)
        // GUIDE_JSON uses the legacy text-only chapter shape (see the legacy test below).
        assertEquals("", guide.chapters[0].intro)
        assertEquals(1, guide.chapters[0].subchapters.size)
        assertEquals("Welcome to the Tower.", guide.chapters[0].subchapters[0].text)
        assertEquals("Head for the Middle Tower.", guide.chapters[0].subchapters[0].directions)

        val location = guide.location
        assertEquals("Tower of London", location?.name)
        assertEquals(51.5081, location?.latitude ?: 0.0, 0.0001)
        assertEquals(-0.0759, location?.longitude ?: 0.0, 0.0001)
        assertEquals(listOf("https://www.hrp.org.uk/tower-of-london/"), guide.sources)
    }

    @Test
    fun guideParsesIntroAndSubchapters() {
        val guide = CustomGuide.parse(SUBCHAPTER_GUIDE_JSON)

        assertEquals(1, guide.chapters.size)
        val chapter = guide.chapters[0]
        assertEquals("The White Tower", chapter.title)
        assertEquals("images/tower-of-london/01.jpg", chapter.image)
        assertEquals("The White Tower from Tower Green", chapter.caption)
        assertEquals("Start at the keep.", chapter.intro)
        assertEquals(2, chapter.subchapters.size)

        val first = chapter.subchapters[0]
        assertEquals("The Chapel of St John", first.title)
        assertEquals("The chapel is on the first floor.", first.text)
        assertEquals("Climb the spiral stair.", first.directions)
        assertEquals("images/tower-of-london/02.jpg", first.image)
        assertEquals("The chapel interior", first.caption)

        val second = chapter.subchapters[1]
        assertEquals("The Crown Jewels", second.title)
        assertEquals("The jewels are next door.", second.text)
    }

    @Test
    fun subchapterOptionalFieldsDefaultToEmpty() {
        val guide = CustomGuide.parse(SUBCHAPTER_GUIDE_JSON)

        val second = guide.chapters[0].subchapters[1]
        assertEquals("", second.directions)
        assertEquals("", second.image)
        assertEquals("", second.caption)
    }

    @Test
    fun legacyTextOnlyChapterMapsToSingleSubchapter() {
        val guide = CustomGuide.parse(GUIDE_JSON)

        val chapter = guide.chapters[0]
        assertEquals("", chapter.intro)
        assertEquals(1, chapter.subchapters.size)
        val subchapter = chapter.subchapters[0]
        assertEquals("", subchapter.title)
        assertEquals("Welcome to the Tower.", subchapter.text)
        assertEquals("Head for the Middle Tower.", subchapter.directions)
        assertEquals("", subchapter.image)
        assertEquals("", subchapter.caption)
    }

    @Test
    fun chapterWithNoSubchaptersAndNoTextDegradesToEmptyList() {
        val guide = CustomGuide.parse(
            """{"id":"bare","title":"Bare Guide","chapters":[{"title":"Empty"}]}""",
        )

        assertEquals(1, guide.chapters.size)
        assertEquals("Empty", guide.chapters[0].title)
        assertEquals("", guide.chapters[0].intro)
        assertTrue(guide.chapters[0].subchapters.isEmpty())
    }

    @Test
    fun guideToleratesMissingOptionalFields() {
        val guide = CustomGuide.parse("""{"id":"bare","title":"Bare Guide"}""")

        assertEquals("bare", guide.id)
        assertTrue(guide.chapters.isEmpty())
        assertTrue(guide.practical.isEmpty())
        assertNull(guide.location)
    }

    private companion object {
        const val PLAYLIST_JSON = """
        {
          "id": "london-2026",
          "title": "London",
          "subtitle": "Day by day",
          "stops": [
            {"id": "tower-of-london", "dayLabel": "Wednesday", "kind": "custom",
             "title": "Tower of London", "subtitle": "Fortress on the Thames", "guideId": "tower-of-london"},
            {"id": "city-walk", "dayLabel": "Wednesday", "kind": "official",
             "title": "City of London Walk", "subtitle": "The Square Mile", "officialMatch": "city of london"},
            {"id": "st-pauls", "dayLabel": "Saturday", "kind": "official",
             "title": "St Paul's Cathedral", "subtitle": "Wren's cathedral", "officialMatch": "paul",
             "note": "Exterior viewing only"}
          ]
        }
        """

        const val GUIDE_JSON = """
        {
          "id": "tower-of-london",
          "title": "Tower of London",
          "subtitle": "Fortress, palace and prison",
          "area": "London",
          "dayLabel": "Wednesday",
          "estimatedMinutes": 150,
          "location": {"name": "Tower of London", "latitude": 51.5081, "longitude": -0.0759},
          "heroImage": "images/tower-of-london/hero.jpg",
          "intro": "A short introduction.",
          "practical": ["Arrive at opening time.", "Book tickets ahead."],
          "mapNote": "Enter by the Middle Tower.",
          "chapters": [
            {"title": "Welcome", "image": "images/tower-of-london/01.jpg", "caption": "The White Tower",
             "text": "Welcome to the Tower.", "directions": "Head for the Middle Tower."},
            {"title": "Crown Jewels", "image": "", "caption": "",
             "text": "The Crown Jewels.", "directions": ""}
          ],
          "sources": ["https://www.hrp.org.uk/tower-of-london/"]
        }
        """

        const val SUBCHAPTER_GUIDE_JSON = """
        {
          "id": "tower-of-london",
          "title": "Tower of London",
          "chapters": [
            {"title": "The White Tower", "image": "images/tower-of-london/01.jpg",
             "caption": "The White Tower from Tower Green",
             "intro": "Start at the keep.",
             "subchapters": [
               {"title": "The Chapel of St John", "text": "The chapel is on the first floor.",
                "directions": "Climb the spiral stair.",
                "image": "images/tower-of-london/02.jpg", "caption": "The chapel interior"},
               {"title": "The Crown Jewels", "text": "The jewels are next door."}
             ]}
          ]
        }
        """
    }
}
