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
        assertEquals("Welcome to the Tower.", guide.chapters[0].text)
        assertEquals("Head for the Middle Tower.", guide.chapters[0].directions)
        assertEquals("images/tower-of-london/01.jpg", guide.chapters[0].image)

        val location = guide.location
        assertEquals("Tower of London", location?.name)
        assertEquals(51.5081, location?.latitude ?: 0.0, 0.0001)
        assertEquals(-0.0759, location?.longitude ?: 0.0, 0.0001)
        assertEquals(listOf("https://www.hrp.org.uk/tower-of-london/"), guide.sources)
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
    }
}
