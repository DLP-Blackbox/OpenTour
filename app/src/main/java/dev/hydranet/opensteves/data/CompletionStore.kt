package dev.hydranet.opensteves.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device record of which guide subchapters and tour chapters the listener
 * has checked off as complete. Stored as a plain string set in
 * SharedPreferences, following the app's other lightweight stores (catalog,
 * playback). Keys are namespaced per content type; see the key helpers.
 */
class CompletionStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _completed = MutableStateFlow(load())
    val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    private fun load(): Set<String> =
        preferences.getStringSet(KEY_COMPLETED, emptySet()).orEmpty().toSet()

    fun isCompleted(key: String): Boolean = key in _completed.value

    @Synchronized
    fun setCompleted(key: String, completed: Boolean) {
        val current = _completed.value
        if ((key in current) == completed) return
        val updated = if (completed) current + key else current - key
        _completed.value = updated
        preferences.edit().putStringSet(KEY_COMPLETED, updated).apply()
    }

    fun toggle(key: String) = setCompleted(key, !isCompleted(key))

    companion object {
        private const val PREFERENCES_NAME = "completion"
        private const val KEY_COMPLETED = "completedKeys"

        /** Key for one subchapter of a bundled custom guide. */
        fun guideSubchapterKey(guideId: String, chapterIndex: Int, subchapterIndex: Int): String =
            "guide:$guideId:$chapterIndex:$subchapterIndex"

        /** Key for one chapter (track) of a downloaded tour, identified by its start offset. */
        fun tourChapterKey(tourId: Int, chapterStartMs: Long): String =
            "tour:$tourId:$chapterStartMs"

        @Volatile private var instance: CompletionStore? = null
        fun get(context: Context): CompletionStore = instance ?: synchronized(this) {
            instance ?: CompletionStore(context.applicationContext).also { instance = it }
        }
    }
}
