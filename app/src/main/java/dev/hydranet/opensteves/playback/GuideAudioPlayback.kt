package dev.hydranet.opensteves.playback

import android.content.Context
import android.media.MediaPlayer
import dev.hydranet.opensteves.data.CustomGuide
import dev.hydranet.opensteves.data.PlaylistRepository
import dev.hydranet.opensteves.session.GuidePlaybackCommand
import dev.hydranet.opensteves.session.GuidePlaybackSnapshot
import dev.hydranet.opensteves.session.GuideSessionPlayback
import dev.hydranet.opensteves.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// The guide screen's playback state, whichever engine produced the current
// item. Playing and Paused carry the position inside the item; both stay 0
// for speech-backed items, which have no seekable position.
sealed interface GuidePlaybackState {
    data object Idle : GuidePlaybackState

    data class Playing(
        val chapterIndex: Int,
        val subchapterIndex: Int = -1,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    ) : GuidePlaybackState

    data class Paused(
        val chapterIndex: Int,
        val subchapterIndex: Int = -1,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    ) : GuidePlaybackState

    data class Error(val message: String) : GuidePlaybackState
}

// Plays a custom guide's bundled narration, the way PlaybackService plays
// tour audio, and bridges it to the Bluetooth session layer through the
// GuideSessionPlayback contract. Items are the same flattened sequence the
// speech player uses — each chapter's intro, then its subchapters — but an
// item with a bundled MP3 (guides/audio/<guideId>/CC-SS-<slug>.mp3, CC the
// 1-based chapter, SS 00 for the intro and 1-based subchapters after that)
// plays through a single-item MediaPlayer that reports and honours real
// positions, which is what makes position-based session sync possible. An
// item without a file falls back to the text-to-speech bridge unchanged:
// GuideTtsPlayback renders it with the v1.11 per-item behaviour and no
// position. Transport controls route through the session while one is
// running and drive the local engines directly otherwise, mirroring
// GuideTtsPlayback.
class GuideAudioPlayback(
    context: Context,
    private val tts: TtsPlayer,
    private val repository: PlaylistRepository,
) : GuideSessionPlayback {
    private val appContext = context.applicationContext
    private val sessions = SessionManager.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fallback = GuideTtsPlayback(context, tts, repository)
    private val loadedGuides = ConcurrentHashMap<String, CustomGuide>()

    @Volatile private var guideTitles: Map<String, String> = emptyMap()
    @Volatile private var currentGuide: CustomGuide? = null
    private var pendingApply: GuidePlaybackSnapshot? = null

    // guideId -> (chapterIndex to subchapterIndex) -> asset path, built once
    // from the bundled audio directory. An item missing here is speech-backed.
    private val audioIndex = ConcurrentHashMap<String, Map<Pair<Int, Int>, String>>()
    @Volatile private var indexReady = false

    private enum class Backend { NONE, AUDIO, TTS }
    private var backend = Backend.NONE

    // The current guide's flattened items and where playback sits in them,
    // mirroring TtsPlayer's sequencing (item list plus a stop-after index).
    private data class GuideAudioItem(
        val chapterIndex: Int,
        val subchapterIndex: Int,
        val assetPath: String?,
    )

    private var items: List<GuideAudioItem> = emptyList()
    private var itemsGuideId: String? = null
    private var itemIndex = -1
    private var stopAfterItemIndex = -1
    private var lastChapterIndex = 0
    private var lastSubchapterIndex = -1

    // The single-item audio engine. Prepared asynchronously; the play and
    // seek intents recorded here are applied when preparation completes.
    private val player = MediaPlayer()
    private var engineGuideId: String? = null
    private var prepared = false
    private var playWhenPrepared = false
    private var seekWhenPrepared = 0L
    private var playingIntent = false
    private var ticker: Job? = null
    private var errorMessage: String? = null

    private val mutableState = MutableStateFlow<GuidePlaybackState>(GuidePlaybackState.Idle)

    /** The unified guide playback state for the guide screen. */
    val state: StateFlow<GuidePlaybackState> = mutableState.asStateFlow()

    private val mutableUiSnapshot = MutableStateFlow<GuidePlaybackSnapshot?>(null)

    /** The session-shaped guide position for UI display, pending state first. */
    val uiSnapshot: StateFlow<GuidePlaybackSnapshot?> = mutableUiSnapshot.asStateFlow()

    /** Invoked when an item finishes playing naturally (not via pause/stop). */
    var onItemFinished: ((chapterIndex: Int, subchapterIndex: Int) -> Unit)? = null

    init {
        player.setOnPreparedListener {
            prepared = true
            if (seekWhenPrepared > 0) runCatching { player.seekTo(seekWhenPrepared.toInt()) }
            seekWhenPrepared = 0
            if (playWhenPrepared) {
                runCatching { player.start() }
                startTicker()
            }
            publishState()
        }
        player.setOnCompletionListener { onEngineCompleted() }
        player.setOnErrorListener { _, _, _ ->
            ticker?.cancel()
            playingIntent = false
            errorMessage = "Audio playback stopped unexpectedly."
            publishState()
            true
        }
        // The speech engine finishing an item only matters while it is the
        // active backend; the fallback's own sequencing carries it from
        // there, and the completion marking is all the UI needs forwarded.
        tts.onItemFinished = { chapterIndex, subchapterIndex ->
            if (backend == Backend.TTS) onItemFinished?.invoke(chapterIndex, subchapterIndex)
        }
        scope.launch(Dispatchers.IO) {
            val guides = repository.loadAllGuides()
            guides.forEach { loadedGuides[it.id] = it }
            guideTitles = guides.associate { it.id to it.title }
            buildAudioIndex()
            indexReady = true
        }
        scope.launch {
            tts.state.collect { if (backend == Backend.TTS) publishState() }
        }
    }

    private fun buildAudioIndex() {
        val built = HashMap<String, Map<Pair<Int, Int>, String>>()
        for (guideId in appContext.assets.list(AUDIO_PATH).orEmpty()) {
            val files = runCatching { appContext.assets.list("$AUDIO_PATH/$guideId") }.getOrNull().orEmpty()
            val byItem = HashMap<Pair<Int, Int>, String>()
            for (name in files) {
                val match = AUDIO_FILE.matchEntire(name) ?: continue
                val chapterIndex = match.groupValues[1].toInt() - 1
                // SS is 00 for the chapter intro (app index -1), 1-based after.
                val subchapterIndex = match.groupValues[2].toInt() - 1
                byItem[chapterIndex to subchapterIndex] = "$AUDIO_PATH/$guideId/$name"
            }
            if (byItem.isNotEmpty()) built[guideId] = byItem
        }
        audioIndex.putAll(built)
    }

    /** The guide the user is looking at; hosting and local playback use it. */
    fun setCurrentGuide(guide: CustomGuide) {
        loadedGuides[guide.id] = guide
        fallback.setCurrentGuide(guide)
        if (currentGuide?.id != guide.id) {
            currentGuide = guide
            if (backend != Backend.AUDIO || !playingIntent) {
                ensureItems(guide)
                itemIndex = -1
                lastChapterIndex = 0
                lastSubchapterIndex = -1
            }
        }
        mutableUiSnapshot.value = sessions.guidePlaybackForUi() ?: snapshot()
    }

    private fun ensureItems(guide: CustomGuide) {
        if (itemsGuideId == guide.id) return
        val audio = audioIndex[guide.id].orEmpty()
        items = buildList {
            guide.chapters.forEachIndexed { chapterIndex, chapter ->
                add(GuideAudioItem(chapterIndex, -1, audio[chapterIndex to -1]))
                chapter.subchapters.forEachIndexed { subchapterIndex, _ ->
                    add(GuideAudioItem(chapterIndex, subchapterIndex, audio[chapterIndex to subchapterIndex]))
                }
            }
        }
        itemsGuideId = guide.id
    }

    // -- GuideSessionPlayback (session-facing) --

    override fun snapshot(): GuidePlaybackSnapshot? {
        if (backend == Backend.TTS) return fallback.snapshot()
        val guide = currentGuide ?: return fallback.snapshot()
        val chapter = lastChapterIndex.coerceIn(0, guide.chapters.lastIndex.coerceAtLeast(0))
        val subchapter = when {
            guide.chapters.isEmpty() -> -1
            else -> lastSubchapterIndex.coerceIn(-1, guide.chapters[chapter].subchapters.lastIndex.coerceAtLeast(0))
        }
        val audio = backend == Backend.AUDIO
        return GuidePlaybackSnapshot(
            guideId = guide.id,
            guideTitle = guide.title,
            chapterIndex = chapter,
            subchapterIndex = subchapter,
            playing = audio && playingIntent,
            positionMs = if (audio) enginePositionMs() else 0,
            durationMs = if (audio) engineDurationMs() else 0,
        )
    }

    override fun isReady(): Boolean = indexReady || fallback.isReady()

    override fun hasGuide(guideId: String): Boolean = guideTitles.containsKey(guideId)

    override fun guideTitle(guideId: String): String? = guideTitles[guideId]

    override fun apply(snapshot: GuidePlaybackSnapshot) {
        val guide = loadedGuides[snapshot.guideId] ?: currentGuide?.takeIf { it.id == snapshot.guideId }
        if (guide == null) {
            pendingApply = snapshot
            scope.launch {
                val loaded = repository.loadGuide(snapshot.guideId)
                if (loaded != null) {
                    loadedGuides[loaded.id] = loaded
                    if (pendingApply == snapshot) {
                        pendingApply = null
                        applyNow(loaded, snapshot)
                    }
                }
            }
            return
        }
        applyNow(guide, snapshot)
    }

    override fun pause() {
        when (backend) {
            Backend.AUDIO -> enginePause()
            Backend.TTS -> fallback.pause()
            Backend.NONE -> Unit
        }
        publishState()
    }

    private fun applyNow(guide: CustomGuide, snapshot: GuidePlaybackSnapshot) {
        currentGuide = guide
        fallback.setCurrentGuide(guide)
        ensureItems(guide)
        val position = items.indexOfFirst {
            it.chapterIndex == snapshot.chapterIndex && it.subchapterIndex == snapshot.subchapterIndex
        }
        val item = items.getOrNull(position)
        if (item?.assetPath == null) {
            // No bundled narration for this item: the speech engine takes
            // over with the v1.11 behaviour, positions ignored.
            engineStop()
            backend = Backend.TTS
            fallback.apply(snapshot)
            publishState()
            return
        }
        if (backend == Backend.TTS) tts.stop()
        backend = Backend.AUDIO
        errorMessage = null
        val sameItem = engineGuideId == guide.id && itemIndex == position
        if (snapshot.playing) {
            if (sameItem) {
                // Already on this item: line the position up if it has
                // drifted materially, and make sure it is actually playing.
                if (abs(enginePositionMs() - snapshot.positionMs) > SEEK_TOLERANCE_MS) {
                    engineSeek(snapshot.positionMs)
                }
                if (!playingIntent) engineResume()
            } else {
                engineLoadItem(position, snapshot.positionMs, play = true, stopAfter = items.lastIndex)
            }
        } else {
            if (sameItem) {
                if (playingIntent) enginePause()
                if (abs(enginePositionMs() - snapshot.positionMs) > SEEK_TOLERANCE_MS) {
                    engineSeek(snapshot.positionMs)
                }
            } else {
                engineLoadItem(position, snapshot.positionMs, play = false, stopAfter = position)
            }
        }
        publishState()
    }

    // -- Transport controls (guide-screen-facing) --
    // Each returns through the session while one is running, so the host's
    // state drives every phone; otherwise it is the local player, unchanged.

    fun speakAll(guide: CustomGuide) {
        setCurrentGuide(guide)
        if (!sessions.requestGuide(GuidePlaybackCommand("jump", 0, -1))) {
            playRange(guide, first = 0, last = items.lastIndex) { fallback.speakAll(guide) }
        }
    }

    fun speakChapter(guide: CustomGuide, chapterIndex: Int) {
        setCurrentGuide(guide)
        if (!sessions.requestGuide(GuidePlaybackCommand("jump", chapterIndex, -1))) {
            val first = items.indexOfFirst { it.chapterIndex == chapterIndex }
            val last = items.indexOfLast { it.chapterIndex == chapterIndex }
            if (first >= 0) playRange(guide, first, last) { fallback.speakChapter(guide, chapterIndex) }
        }
    }

    fun speakSubchapter(guide: CustomGuide, chapterIndex: Int, subchapterIndex: Int) {
        setCurrentGuide(guide)
        if (!sessions.requestGuide(GuidePlaybackCommand("jump", chapterIndex, subchapterIndex))) {
            val position = items.indexOfFirst {
                it.chapterIndex == chapterIndex && it.subchapterIndex == subchapterIndex
            }
            if (position >= 0) {
                playRange(guide, position, position) { fallback.speakSubchapter(guide, chapterIndex, subchapterIndex) }
            }
        }
    }

    fun requestPause() {
        if (!sessions.requestGuide(GuidePlaybackCommand("pause"))) {
            when (backend) {
                Backend.AUDIO -> enginePause()
                Backend.TTS -> fallback.requestPause()
                Backend.NONE -> Unit
            }
            publishState()
        }
    }

    fun requestResume() {
        if (!sessions.requestGuide(GuidePlaybackCommand("play"))) {
            when (backend) {
                Backend.AUDIO -> engineResume()
                Backend.TTS -> fallback.requestResume()
                Backend.NONE -> Unit
            }
            publishState()
        }
    }

    fun requestStop() {
        if (!sessions.requestGuide(GuidePlaybackCommand("stop"))) stopLocal()
    }

    fun requestSeek(positionMs: Long) {
        if (!sessions.requestGuide(GuidePlaybackCommand("seek", positionMs = positionMs))) {
            if (backend == Backend.AUDIO) engineSeek(positionMs)
            publishState()
        }
    }

    /** Stops local playback outright, with no session routing. Used when the
     * guide screen goes away; a session's playback is guarded by the caller,
     * mirroring how the screen used to stop the speech engine directly. */
    fun stopLocal() {
        engineStop()
        if (backend == Backend.TTS) tts.stop()
        backend = Backend.NONE
        itemIndex = -1
        publishState()
    }

    // Starts the flattened range on the audio engine, or hands the whole
    // range to the speech bridge when its first item has no bundled file.
    private fun playRange(guide: CustomGuide, first: Int, last: Int, speech: () -> Unit) {
        ensureItems(guide)
        val start = items.getOrNull(first) ?: return
        if (start.assetPath == null) {
            engineStop()
            backend = Backend.TTS
            speech()
            publishState()
            return
        }
        if (backend == Backend.TTS) tts.stop()
        backend = Backend.AUDIO
        errorMessage = null
        engineLoadItem(first, startAtMs = 0, play = true, stopAfter = last)
        publishState()
    }

    // -- The single-item audio engine --

    private fun engineLoadItem(position: Int, startAtMs: Long, play: Boolean, stopAfter: Int) {
        val item = items.getOrNull(position) ?: return
        val path = item.assetPath ?: return
        ticker?.cancel()
        itemIndex = position
        stopAfterItemIndex = stopAfter
        engineGuideId = currentGuide?.id
        lastChapterIndex = item.chapterIndex
        lastSubchapterIndex = item.subchapterIndex
        prepared = false
        playWhenPrepared = play
        playingIntent = play
        seekWhenPrepared = startAtMs
        val loaded = runCatching {
            player.reset()
            appContext.assets.openFd(path).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.prepareAsync()
        }.isSuccess
        if (!loaded) {
            playingIntent = false
            errorMessage = "Could not play the bundled audio."
        }
        publishState()
    }

    private fun onEngineCompleted() {
        ticker?.cancel()
        playingIntent = false
        val finished = items.getOrNull(itemIndex)
        if (finished != null) onItemFinished?.invoke(finished.chapterIndex, finished.subchapterIndex)
        val next = itemIndex + 1
        when {
            finished != null && itemIndex < stopAfterItemIndex && next in items.indices -> {
                val nextItem = items[next]
                if (nextItem.assetPath != null) {
                    engineLoadItem(next, startAtMs = 0, play = true, stopAfter = stopAfterItemIndex)
                } else {
                    // The narration runs out here: the speech engine reads
                    // the rest of the guide, as it did before the bundle.
                    val guide = currentGuide
                    backend = Backend.TTS
                    if (guide != null) fallback.speakFrom(guide, nextItem.chapterIndex, nextItem.subchapterIndex)
                }
            }
            else -> {
                itemIndex = -1
                prepared = false
                runCatching { player.reset() }
            }
        }
        publishState()
        // The host's player advancing on its own is what guests follow;
        // deliberate commands are broadcast by the session layer itself.
        sessions.onGuideStateChanged()
    }

    private fun enginePause() {
        if (prepared && player.isPlaying) runCatching { player.pause() }
        playingIntent = false
        playWhenPrepared = false
        ticker?.cancel()
        publishState()
    }

    private fun engineResume() {
        playingIntent = true
        if (prepared) {
            runCatching { player.start() }
            startTicker()
        } else {
            playWhenPrepared = true
        }
        publishState()
    }

    private fun engineSeek(positionMs: Long) {
        if (prepared) runCatching { player.seekTo(positionMs.coerceAtLeast(0).toInt()) }
        else seekWhenPrepared = positionMs
        publishState()
    }

    private fun engineStop() {
        ticker?.cancel()
        playingIntent = false
        playWhenPrepared = false
        prepared = false
        engineGuideId = null
        runCatching { player.reset() }
    }

    private fun enginePositionMs(): Long =
        if (prepared) runCatching { player.currentPosition.toLong() }.getOrDefault(0) else seekWhenPrepared

    private fun engineDurationMs(): Long =
        if (prepared) runCatching { player.duration.toLong() }.getOrDefault(0).coerceAtLeast(0) else 0

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && backend == Backend.AUDIO && playingIntent) {
                delay(POSITION_TICK_MS)
                publishState()
            }
        }
    }

    private fun publishState() {
        errorMessage?.let {
            mutableState.value = GuidePlaybackState.Error(it)
            mutableUiSnapshot.value = sessions.guidePlaybackForUi() ?: snapshot()
            return
        }
        mutableState.value = when (backend) {
            Backend.TTS -> when (val speech = tts.state.value) {
                is TtsState.Speaking -> GuidePlaybackState.Playing(speech.chapterIndex, speech.subchapterIndex)
                is TtsState.Paused -> GuidePlaybackState.Paused(speech.chapterIndex, speech.subchapterIndex)
                is TtsState.Error -> GuidePlaybackState.Error(speech.message)
                else -> GuidePlaybackState.Idle
            }
            Backend.AUDIO -> {
                val item = items.getOrNull(itemIndex)
                when {
                    item == null -> GuidePlaybackState.Idle
                    playingIntent -> GuidePlaybackState.Playing(
                        item.chapterIndex, item.subchapterIndex, enginePositionMs(), engineDurationMs(),
                    )
                    else -> GuidePlaybackState.Paused(
                        item.chapterIndex, item.subchapterIndex, enginePositionMs(), engineDurationMs(),
                    )
                }
            }
            Backend.NONE -> GuidePlaybackState.Idle
        }
        mutableUiSnapshot.value = sessions.guidePlaybackForUi() ?: snapshot()
    }

    private companion object {
        const val AUDIO_PATH = "guides/audio"
        const val SEEK_TOLERANCE_MS = 1_000L
        const val POSITION_TICK_MS = 250L
        val AUDIO_FILE = Regex("""(\d+)-(\d+)-.+\.mp3""")
    }
}
