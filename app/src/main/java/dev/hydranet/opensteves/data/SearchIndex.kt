package dev.hydranet.opensteves.data

import java.util.Locale

/** What a single search result points at; the UI groups results by kind. */
enum class SearchResultKind { TOUR, PLAYLIST, STOP, GUIDE, CHAPTER, WIKIPEDIA }

/**
 * One match from [SearchIndex]. [title] is the primary line, [context] names
 * the tour, playlist, or guide it belongs to, and [snippet] carries an
 * excerpt when the match came from body text. The id fields are the
 * navigation target; which are set depends on [kind].
 */
data class SearchResult(
    val kind: SearchResultKind,
    val title: String,
    val context: String,
    val snippet: String = "",
    val tourId: Int? = null,
    val playlistId: String? = null,
    val stopId: String? = null,
    val guideId: String? = null,
    val chapterIndex: Int? = null,
)

/**
 * Offline search over everything already on the device: the tour catalog,
 * playlists and their stops, the bundled custom guides (chapter and
 * subchapter titles and narration text), and the saved Wikipedia articles.
 * The index is built once from the loaded repositories with the searchable
 * text pre-lowered, so searching a keystroke never re-reads assets.
 * Matching is plain case-insensitive substring matching; title matches rank
 * above context matches, which rank above body-text matches.
 */
class SearchIndex private constructor(private val entries: List<Entry>) {

    private data class Entry(
        val result: SearchResult,
        val title: String,
        val context: String,
        val body: String,
        val bodySource: String,
    )

    fun search(query: String): List<SearchResult> {
        val term = query.trim().lowercase(Locale.ROOT)
        if (term.length < MIN_QUERY_LENGTH) return emptyList()
        val ranked = entries.mapNotNull { entry ->
            val rank = when {
                entry.title.contains(term) -> 0
                entry.context.contains(term) -> 1
                entry.body.contains(term) -> 2
                else -> return@mapNotNull null
            }
            val result = if (rank == 2) {
                entry.result.copy(snippet = snippet(entry.bodySource, term))
            } else {
                entry.result
            }
            Triple(entry.result.kind, rank, result)
        }
        return ranked.groupBy { it.first }
            .entries.sortedBy { it.key.ordinal }
            .flatMap { (_, group) -> group.sortedBy { it.second }.take(MAX_RESULTS_PER_KIND).map { it.third } }
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_RESULTS_PER_KIND = 25
        private const val SNIPPET_RADIUS = 60

        private fun snippet(source: String, term: String): String {
            val index = source.lowercase(Locale.ROOT).indexOf(term)
            if (index < 0) return ""
            val start = (index - SNIPPET_RADIUS).coerceAtLeast(0)
            val end = (index + term.length + SNIPPET_RADIUS).coerceAtMost(source.length)
            val text = source.substring(start, end).replace(Regex("\\s+"), " ").trim()
            return (if (start > 0) "…" else "") + text + (if (end < source.length) "…" else "")
        }

        fun build(
            tours: List<Tour>,
            playlists: List<Playlist>,
            guides: List<CustomGuide>,
            wikipedia: List<WikipediaArticle>,
        ): SearchIndex {
            val entries = mutableListOf<Entry>()
            fun add(result: SearchResult, body: String = "") {
                entries += Entry(
                    result = result,
                    title = result.title.lowercase(Locale.ROOT),
                    context = result.context.lowercase(Locale.ROOT),
                    body = body.lowercase(Locale.ROOT),
                    bodySource = body,
                )
            }
            tours.forEach { tour ->
                add(
                    SearchResult(SearchResultKind.TOUR, tour.title, tour.region, tourId = tour.id),
                    body = listOf(tour.description, tour.details).filter(String::isNotBlank).joinToString("\n"),
                )
            }
            playlists.forEach { playlist ->
                add(SearchResult(SearchResultKind.PLAYLIST, playlist.title, playlist.subtitle, playlistId = playlist.id))
                playlist.stops.forEach { stop ->
                    add(
                        SearchResult(
                            SearchResultKind.STOP, stop.title, playlist.title,
                            playlistId = playlist.id, stopId = stop.id, guideId = stop.guideId,
                        ),
                        body = listOf(stop.subtitle, stop.note.orEmpty()).filter(String::isNotBlank).joinToString("\n"),
                    )
                }
            }
            val guideTitles = guides.associate { it.id to it.title }
            guides.forEach { guide ->
                add(
                    SearchResult(SearchResultKind.GUIDE, guide.title, guide.area, guideId = guide.id),
                    body = guide.intro,
                )
                guide.chapters.forEachIndexed { chapterIndex, chapter ->
                    val chapterBody = buildString {
                        append(chapter.intro)
                        chapter.subchapters.forEach { subchapter ->
                            append("\n").append(subchapter.title)
                            append("\n").append(subchapter.text)
                            if (subchapter.directions.isNotBlank()) append("\n").append(subchapter.directions)
                        }
                    }
                    add(
                        SearchResult(
                            SearchResultKind.CHAPTER, chapter.title, guide.title,
                            guideId = guide.id, chapterIndex = chapterIndex,
                        ),
                        body = chapterBody,
                    )
                    // Subchapter titles get their own entry (title match only;
                    // their text is already covered by the chapter entry) so a
                    // specific stop within a chapter is directly tappable.
                    chapter.subchapters.forEach { subchapter ->
                        if (subchapter.title.isNotBlank() && !subchapter.title.equals(chapter.title, ignoreCase = true)) {
                            add(
                                SearchResult(
                                    SearchResultKind.CHAPTER, subchapter.title, "${guide.title} · ${chapter.title}",
                                    guideId = guide.id, chapterIndex = chapterIndex,
                                ),
                            )
                        }
                    }
                }
            }
            wikipedia.forEach { article ->
                add(
                    SearchResult(
                        SearchResultKind.WIKIPEDIA, article.title,
                        guideTitles[article.guideId]?.let { "$it · Wikipedia" } ?: "Wikipedia",
                        guideId = article.guideId,
                    ),
                    body = article.text,
                )
            }
            return SearchIndex(entries)
        }
    }
}
