package dev.hydranet.opensteves.playback

import android.content.Context
import dev.hydranet.opensteves.data.CustomGuide
import dev.hydranet.opensteves.data.PlaylistRepository
import dev.hydranet.opensteves.session.GuidePlaybackCommand
import dev.hydranet.opensteves.session.GuidePlaybackSnapshot
import dev.hydranet.opensteves.session.GuideSessionPlayback
import dev.hydranet.opensteves.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// Bridges the custom-guide text-to-speech player and the Bluetooth session
// layer, the way PlaybackService bridges tour audio. It implements the
// session-facing GuideSessionPlayback contract (snapshots in guide positions,
// applied to the local speech engine) and exposes the guide screen's transport
// controls, which route through the session while one is running — mirroring
// PlaybackConnection routing tour controls through SessionManager.request —
// and drive the local player directly otherwise.
class GuideTtsPlayback(
    context: Context,
    private val tts: TtsPlayer,
    private val repository: PlaylistRepository,
) : GuideSessionPlayback {
    private val sessions = SessionManager.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loadedGuides = ConcurrentHashMap<String, CustomGuide>()

    @Volatile private var guideTitles: Map<String, String> = emptyMap()
    @Volatile private var currentGuide: CustomGuide? = null
    private var lastChapterIndex = 0
    private var lastSubchapterIndex = -1
    private var pendingApply: GuidePlaybackSnapshot? = null

    private val mutableUiSnapshot = MutableStateFlow<GuidePlaybackSnapshot?>(null)

    /** The session-shaped guide position for UI display, pending state first. */
    val uiSnapshot: StateFlow<GuidePlaybackSnapshot?> = mutableUiSnapshot.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val guides = repository.loadAllGuides()
            guides.forEach { loadedGuides[it.id] = it }
            guideTitles = guides.associate { it.id to it.title }
        }
        scope.launch {
            var previous: TtsState = TtsState.Idle
            tts.state.collect { state ->
                val prev = previous
                when (state) {
                    is TtsState.Speaking -> {
                        lastChapterIndex = state.chapterIndex
                        lastSubchapterIndex = state.subchapterIndex
                    }
                    is TtsState.Paused -> {
                        lastChapterIndex = state.chapterIndex
                        lastSubchapterIndex = state.subchapterIndex
                    }
                    else -> Unit
                }
                // The host's speech engine moving to a new item (or stopping)
                // on its own is what guests follow; deliberate commands are
                // broadcast by the session layer itself when they are made.
                val advanced = when {
                    state is TtsState.Speaking && (prev !is TtsState.Speaking ||
                        prev.chapterIndex != state.chapterIndex ||
                        prev.subchapterIndex != state.subchapterIndex) -> true
                    prev is TtsState.Speaking && state !is TtsState.Speaking -> true
                    else -> false
                }
                previous = state
                mutableUiSnapshot.value = sessions.guidePlaybackForUi() ?: snapshot()
                if (advanced) sessions.onGuideStateChanged()
            }
        }
    }

    /** The guide the user is looking at; hosting and local playback use it. */
    fun setCurrentGuide(guide: CustomGuide) {
        loadedGuides[guide.id] = guide
        if (currentGuide?.id != guide.id) {
            currentGuide = guide
            if (tts.state.value !is TtsState.Speaking && tts.state.value !is TtsState.Paused) {
                lastChapterIndex = 0
                lastSubchapterIndex = -1
            }
        }
        mutableUiSnapshot.value = sessions.guidePlaybackForUi() ?: snapshot()
    }

    // -- GuideSessionPlayback (session-facing) --

    override fun snapshot(): GuidePlaybackSnapshot? {
        val guide = currentGuide ?: return null
        val chapter = lastChapterIndex.coerceIn(0, guide.chapters.lastIndex.coerceAtLeast(0))
        val subchapter = when {
            guide.chapters.isEmpty() -> -1
            else -> lastSubchapterIndex.coerceIn(-1, guide.chapters[chapter].subchapters.lastIndex.coerceAtLeast(0))
        }
        return GuidePlaybackSnapshot(
            guideId = guide.id,
            guideTitle = guide.title,
            chapterIndex = chapter,
            subchapterIndex = subchapter,
            playing = tts.state.value is TtsState.Speaking,
        )
    }

    override fun isReady(): Boolean = tts.isEngineReady

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
        tts.pause()
    }

    private fun applyNow(guide: CustomGuide, snapshot: GuidePlaybackSnapshot) {
        currentGuide = guide
        val state = tts.state.value
        if (snapshot.playing) {
            val alreadySpeaking = state is TtsState.Speaking &&
                state.chapterIndex == snapshot.chapterIndex && state.subchapterIndex == snapshot.subchapterIndex
            if (!alreadySpeaking) tts.speakFrom(guide, snapshot.chapterIndex, snapshot.subchapterIndex)
        } else {
            when {
                state is TtsState.Speaking &&
                    state.chapterIndex == snapshot.chapterIndex &&
                    state.subchapterIndex == snapshot.subchapterIndex -> tts.pause()
                state is TtsState.Paused &&
                    state.chapterIndex == snapshot.chapterIndex &&
                    state.subchapterIndex == snapshot.subchapterIndex -> Unit
                else -> tts.cueSubchapter(guide, snapshot.chapterIndex, snapshot.subchapterIndex)
            }
        }
    }

    // -- Transport controls (guide-screen-facing) --
    // Each returns through the session while one is running, so the host's
    // state drives every phone; otherwise it is the local player, unchanged.

    fun speakAll(guide: CustomGuide) {
        setCurrentGuide(guide)
        if (!sessions.requestGuide(GuidePlaybackCommand("jump", 0, -1))) tts.speakAll(guide)
    }

    fun speakChapter(guide: CustomGuide, chapterIndex: Int) {
        setCurrentGuide(guide)
        if (!sessions.requestGuide(GuidePlaybackCommand("jump", chapterIndex, -1))) {
            tts.speakChapter(guide, chapterIndex)
        }
    }

    fun speakSubchapter(guide: CustomGuide, chapterIndex: Int, subchapterIndex: Int) {
        setCurrentGuide(guide)
        if (!sessions.requestGuide(GuidePlaybackCommand("jump", chapterIndex, subchapterIndex))) {
            tts.speakSubchapter(guide, chapterIndex, subchapterIndex)
        }
    }

    fun requestPause() {
        if (!sessions.requestGuide(GuidePlaybackCommand("pause"))) tts.pause()
    }

    fun requestResume() {
        if (!sessions.requestGuide(GuidePlaybackCommand("play"))) tts.resume()
    }

    fun requestStop() {
        if (!sessions.requestGuide(GuidePlaybackCommand("stop"))) tts.stop()
    }
}
