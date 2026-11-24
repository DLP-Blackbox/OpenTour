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
    data class Speaking(val chapterIndex: Int) : TtsState
    data class Paused(val chapterIndex: Int) : TtsState
    data class Error(val message: String) : TtsState
}

// Reads a custom guide aloud with the built-in Android text-to-speech engine.
// TextToSpeech has no true pause: pause() stops the engine and remembers the
// chapter, and resume() speaks that chapter again from the beginning.
class TtsPlayer(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var ready = false
    private var failed = false
    private var guide: CustomGuide? = null
    private var chapterIndex = -1
    private var playAll = false
    private var pending: (() -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val current = guide ?: return
                if (playAll && chapterIndex + 1 < current.chapters.size) {
                    speakChapterAt(chapterIndex + 1)
                } else {
                    playAll = false
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

    fun speakChapter(guide: CustomGuide, index: Int) {
        if (index !in guide.chapters.indices) return
        this.guide = guide
        playAll = false
        whenReady { speakChapterAt(index) }
    }

    fun speakAll(guide: CustomGuide, fromIndex: Int = 0) {
        if (guide.chapters.isEmpty()) return
        this.guide = guide
        playAll = true
        whenReady { speakChapterAt(fromIndex.coerceIn(guide.chapters.indices)) }
    }

    fun pause() {
        val current = _state.value
        if (current is TtsState.Speaking) {
            tts.stop()
            _state.value = TtsState.Paused(current.chapterIndex)
        }
    }

    fun resume() {
        val current = _state.value
        if (current is TtsState.Paused) whenReady { speakChapterAt(current.chapterIndex) }
    }

    fun stop() {
        pending = null
        playAll = false
        tts.stop()
        if (!failed) _state.value = TtsState.Idle
    }

    fun next() {
        val current = guide ?: return
        if (chapterIndex + 1 < current.chapters.size) whenReady { speakChapterAt(chapterIndex + 1) }
    }

    fun previous() {
        if (guide == null) return
        if (chapterIndex > 0) whenReady { speakChapterAt(chapterIndex - 1) }
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

    private fun speakChapterAt(index: Int) {
        val current = guide ?: return
        val chapter = current.chapters.getOrNull(index) ?: return
        chapterIndex = index
        val result = tts.speak(chapter.text, TextToSpeech.QUEUE_FLUSH, null, "guide-chapter-$index")
        if (result == TextToSpeech.ERROR) {
            _state.value = TtsState.Error("Could not start text-to-speech.")
        } else {
            _state.value = TtsState.Speaking(index)
        }
    }
}
