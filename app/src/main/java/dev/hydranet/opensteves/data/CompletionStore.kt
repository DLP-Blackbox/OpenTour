package dev.hydranet.opensteves.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * On-device record of which guide subchapters and tour chapters the listener
 * has checked off as complete. Stored as a plain string set in
 * SharedPreferences, following the app's other lightweight stores (catalog,
 * playback). Keys are namespaced per content type; see the key helpers.
 *
 * SharedPreferences are wiped when the app is uninstalled, so the set is
 * also mirrored to a small JSON file in the public Downloads folder
 * ([BACKUP_FILE_NAME], via MediaStore — no storage permission on API 29+).
 * On a fresh install whose internal set is empty, the backup is restored;
 * a non-empty internal set always wins, so an older file can never clobber
 * newer state. The mirror is a local file only — no network, no cloud
 * backup — and every failure around it is tolerated silently: the internal
 * store keeps working exactly as before.
 */
class CompletionStore private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: Job? = null
    private val _completed = MutableStateFlow(load())
    val completed: StateFlow<Set<String>> = _completed.asStateFlow()

    init {
        if (_completed.value.isEmpty()) scope.launch { restoreFromBackup() }
    }

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
        scheduleBackup()
    }

    fun toggle(key: String) = setCompleted(key, !isCompleted(key))

    // Writes are debounced so a burst of check-offs (or a whole chapter
    // toggled at once) coalesces into a single file write.
    private fun scheduleBackup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(BACKUP_DEBOUNCE_MS)
            writeBackup(_completed.value)
        }
    }

    private suspend fun restoreFromBackup() {
        val restored = readBackup()
        if (restored.isEmpty()) return
        synchronized(this) {
            // Internal state wins: if anything was checked off while the
            // restore was in flight, the file is the older copy — drop it.
            if (_completed.value.isNotEmpty()) return
            _completed.value = restored
            preferences.edit().putStringSet(KEY_COMPLETED, restored).apply()
        }
    }

    private fun readBackup(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        return runCatching {
            val uri = findBackupUri() ?: return@runCatching emptySet()
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@runCatching emptySet()
            decodeBackup(text)
        }.getOrDefault(emptySet())
    }

    private fun writeBackup(keys: Set<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val resolver = context.contentResolver
            val uri = findBackupUri() ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                },
            ) ?: return@runCatching
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(encodeBackup(keys).toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun findBackupUri(): Uri? = runCatching {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(BACKUP_FILE_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }.getOrNull()

    companion object {
        private const val PREFERENCES_NAME = "completion"
        private const val KEY_COMPLETED = "completedKeys"
        private const val BACKUP_DEBOUNCE_MS = 1_000L

        /** Backup file mirrored to the public Downloads folder. */
        const val BACKUP_FILE_NAME = "OpenTour-completion-backup.json"

        /** Serializes the completed set as the backup file's JSON array. */
        internal fun encodeBackup(keys: Set<String>): String = JSONArray(keys.sorted()).toString()

        /** Parses a backup file; throws on corrupt content — callers tolerate. */
        internal fun decodeBackup(text: String): Set<String> {
            val array = JSONArray(text)
            return List(array.length()) { array.getString(it) }.toSet()
        }

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
