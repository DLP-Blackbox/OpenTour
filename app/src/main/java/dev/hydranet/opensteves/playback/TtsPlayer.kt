package dev.hydranet.opensteves.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.hydranet.opensteves.data.CustomGuide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface TtsState {
    data object Idle : TtsState

    // subchapterIndex is -1 when the item being spoken is the chapter intro.
    data class Speaking(val chapterIndex: Int, val subchapterIndex: Int = -1) : TtsState
    data class Paused(val chapterIndex: Int, val subchapterIndex: Int = -1) : TtsState
    data class Error(val message: String) : TtsState
}

// Reads a custom guide aloud with the built-in Android text-to-speech engine.
// Playback is a flattened sequence of speakable items: for each chapter in
// order, its intro (when non-blank) followed by each subchapter's text.
// TextToSpeech has no true pause: pause() stops the engine and remembers the
// item, and resume() speaks that item again from the beginning.
class TtsPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var ready = false
    private var failed = false
    private var guide: CustomGuide? = null
    private var items: List<SpeakableItem> = emptyList()
    private var itemIndex = -1
    private var stopAfterItemIndex = -1
    private var pending: (() -> Unit)? = null
    private var suppressFinish = false

    /** Invoked when an item finishes playing naturally (not via pause/stop). */
    var onItemFinished: ((chapterIndex: Int, subchapterIndex: Int) -> Unit)? = null

    private data class SpeakableItem(
        val chapterIndex: Int,
        val subchapterIndex: Int,
        val text: String,
    )

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val finished = items.getOrNull(itemIndex)
                if (!suppressFinish && finished != null) {
                    onItemFinished?.invoke(finished.chapterIndex, finished.subchapterIndex)
                }
                if (itemIndex in items.indices && itemIndex < stopAfterItemIndex) {
                    speakItemAt(itemIndex + 1)
                } else {
                    _state.value = TtsState.Idle
                }
            }

            @Deprecated("Deprecated in the framework listener")
            override fun onError(utteranceId: String?) {
                _state.value = TtsState.Error("Text-to-speech stopped unexpectedly.")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _state.value = TtsState.Error("Text-to-speech stopped unexpectedly.")
            }
        })
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            _state.value = TtsState.Error("Text-to-speech is not available on this device.")
            return
        }
        val british = tts.setLanguage(Locale.UK)
        if (british == TextToSpeech.LANG_MISSING_DATA || british == TextToSpeech.LANG_NOT_SUPPORTED) {
            val american = tts.setLanguage(Locale.US)
            if (american == TextToSpeech.LANG_MISSING_DATA || american == TextToSpeech.LANG_NOT_SUPPORTED) {
                failed = true
                _state.value = TtsState.Error("No English voice is installed for text-to-speech.")
                return
            }
        }
        ready = true
        val action = pending
        pending = null
        action?.invoke()
    }

    /** Plays just the one item: a subchapter, or the chapter intro when [subchapterIndex] is -1. */
    fun speakSubchapter(guide: CustomGuide, chapterIndex: Int, subchapterIndex: Int) {
        val position = flatten(guide).indexOfFirst {
            it.chapterIndex == chapterIndex && it.subchapterIndex == subchapterIndex
        }
        if (position < 0) return
        setGuide(guide)
        whenReady { startAt(position, stopAfter = position) }
    }

    /** Plays a chapter's items in order — intro, then subchapters — and stops at its end. */
    fun speakChapter(guide: CustomGuide, index: Int) {
        if (index !in guide.chapters.indices) return
        setGuide(guide)
        val first = items.indexOfFirst { it.chapterIndex == index }
        if (first < 0) return
        val last = items.indexOfLast { it.chapterIndex == index }
        whenReady { startAt(first, stopAfter = last) }
    }

    /** Plays everything from [fromChapterIndex] to the end of the guide. */
    fun speakAll(guide: CustomGuide, fromChapterIndex: Int = 0) {
        if (guide.chapters.isEmpty()) return
        setGuide(guide)
        val first = items.indexOfFirst { it.chapterIndex >= fromChapterIndex.coerceAtLeast(0) }
        if (first < 0) return
        whenReady { startAt(first, stopAfter = items.lastIndex) }
    }

    fun pause() {
        val current = _state.value
        if (current is TtsState.Speaking) {
            suppressFinish = true
            tts.stop()
            _state.value = TtsState.Paused(current.chapterIndex, current.subchapterIndex)
        }
    }

    fun resume() {
        val current = _state.value
        if (current is TtsState.Paused && itemIndex in items.indices) {
            whenReady { speakItemAt(itemIndex) }
        }
    }

    fun stop() {
        pending = null
        suppressFinish = true
        tts.stop()
        if (!failed) _state.value = TtsState.Idle
    }

    fun next() {
        if (itemIndex in items.indices && itemIndex + 1 < items.size) {
            val target = itemIndex + 1
            whenReady { startAt(target, stopAfter = target) }
        }
    }

    fun previous() {
        if (itemIndex > 0) {
            val target = itemIndex - 1
            whenReady { startAt(target, stopAfter = target) }
        }
    }

    fun release() {
        pending = null
        tts.stop()
        tts.shutdown()
    }

    private fun whenReady(action: () -> Unit) {
        if (failed) return
        if (ready) action() else pending = action
    }

    private fun setGuide(guide: CustomGuide) {
        if (this.guide != guide) {
            this.guide = guide
            items = flatten(guide)
            itemIndex = -1
            stopAfterItemIndex = -1
        }
    }

    private fun flatten(guide: CustomGuide): List<SpeakableItem> = buildList {
        guide.chapters.forEachIndexed { chapterIndex, chapter ->
            if (chapter.intro.isNotBlank()) add(SpeakableItem(chapterIndex, -1, chapter.intro))
            chapter.subchapters.forEachIndexed { subchapterIndex, subchapter ->
                if (subchapter.text.isNotBlank()) add(SpeakableItem(chapterIndex, subchapterIndex, subchapter.text))
            }
        }
    }

    private fun startAt(position: Int, stopAfter: Int) {
        stopAfterItemIndex = stopAfter
        speakItemAt(position)
    }

    private fun speakItemAt(position: Int) {
        val item = items.getOrNull(position) ?: return
        suppressFinish = false
        itemIndex = position
        val result = tts.speak(
            item.text, TextToSpeech.QUEUE_FLUSH, null,
            "guide-${item.chapterIndex}-${item.subchapterIndex}",
        )
        if (result == TextToSpeech.ERROR) {
            _state.value = TtsState.Error("Could not start text-to-speech.")
        } else {
            _state.value = TtsState.Speaking(item.chapterIndex, item.subchapterIndex)
        }
    }
}
