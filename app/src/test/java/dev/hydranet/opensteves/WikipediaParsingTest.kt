package dev.hydranet.opensteves

import dev.hydranet.opensteves.data.WikipediaArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaParsingTest {
    @Test
    fun articleParsesAllFields() {
        val article = WikipediaArticle.parse(ARTICLE_JSON)

        assertEquals("tower-of-london", article.guideId)
        assertEquals("Tower of London", article.title)
        assertEquals("https://en.wikipedia.org/wiki/Tower_of_London", article.sourceUrl)
        assertEquals("2026-11-24", article.retrieved)
        assertTrue(article.text.startsWith("The Tower of London"))
        assertTrue(article.text.contains("\n\n"))
    }

    @Test
    fun articleToleratesMissingOptionalFields() {
        val article = WikipediaArticle.parse("""{"guideId":"bare","title":"Bare Article"}""")

        assertEquals("bare", article.guideId)
        assertEquals("Bare Article", article.title)
        assertEquals("", article.sourceUrl)
        assertEquals("", article.retrieved)
        assertEquals("", article.text)
    }

    private companion object {
        const val ARTICLE_JSON = """
        {
          "guideId": "tower-of-london",
          "title": "Tower of London",
          "sourceUrl": "https://en.wikipedia.org/wiki/Tower_of_London",
          "retrieved": "2026-11-24",
          "text": "The Tower of London is a historic castle.\n\nIt was founded in 1066."
        }
        """
    }
}
