package dev.hydranet.opensteves.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class PlaylistRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val guides = ConcurrentHashMap<String, CustomGuide>()

    init {
        scope.launch { _playlists.value = loadPlaylists() }
    }

    private fun loadPlaylists(): List<Playlist> = runCatching {
        val assets = context.assets
        assets.list(PLAYLISTS_PATH).orEmpty()
            .filter { it.endsWith(".json") }
            .sorted()
            .mapNotNull { name ->
                runCatching {
                    assets.open("$PLAYLISTS_PATH/$name").bufferedReader().use { Playlist.parse(it.readText()) }
                }.getOrNull()
            }
    }.getOrDefault(emptyList())

    // Guide files are written by content authors and may not exist yet for a
    // stop; a missing or unreadable guide returns null instead of failing.
    suspend fun loadGuide(id: String): CustomGuide? {
        guides[id]?.let { return it }
        val guide = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("$GUIDES_PATH/$id.json").bufferedReader().use { CustomGuide.parse(it.readText()) }
            }.getOrNull()
        }
        if (guide != null) guides[id] = guide
        return guide
    }

    // Official stops name a tour by a lowercase substring of its title; the
    // match is resolved against whatever tours the catalog currently holds.
    fun findOfficialTour(stop: PlaylistStop, tours: List<Tour>): Tour? {
        val match = stop.officialMatch?.takeIf { it.isNotBlank() } ?: return null
        return tours.firstOrNull { it.title.contains(match, ignoreCase = true) }
    }

    companion object {
        const val PLAYLISTS_PATH = "playlists"
        const val GUIDES_PATH = "guides"

        @Volatile private var instance: PlaylistRepository? = null
        fun get(context: Context): PlaylistRepository = instance ?: synchronized(this) {
            instance ?: PlaylistRepository(context.applicationContext).also { instance = it }
        }
    }
}
