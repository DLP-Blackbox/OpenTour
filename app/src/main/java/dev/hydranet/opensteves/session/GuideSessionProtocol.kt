package dev.hydranet.opensteves.session

// A guide session mirrors a tour session, but the shared content is a bundled
// custom guide identified by its guideId: both apps already carry identical
// guide content, so nothing is transferred. Playback state is a position in
// the guide (chapter / subchapter) plus a playing flag, and — since v1.12 —
// a position inside the current item for guides backed by bundled audio.
// Audio-backed playback does proper position projection and drift correction
// like SessionTiming; text-to-speech cannot seek inside an utterance, so a
// snapshot with durationMs == 0 (and positionMs == 0) is a TTS-backed state
// and degrades to the v1.11 per-subchapter behaviour: position fields are
// ignored and no seeks are ever derived from it.
data class GuidePlaybackSnapshot(
    val guideId: String,
    val guideTitle: String = "",
    val chapterIndex: Int = 0,
    val subchapterIndex: Int = -1,
    val playing: Boolean = false,
    // Position within the current item, and the item's duration. Both stay 0
    // for TTS-backed playback, where there is no seekable position.
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

data class GuidePlaybackCommand(
    val action: String,
    val chapterIndex: Int = 0,
    val subchapterIndex: Int = -1,
    // Target position within the current item for "seek"; "play"/"pause" may
    // also carry one, and "jump" may carry one as the offset into the item it
    // lands on (default 0 = the item's start).
    val positionMs: Long = 0,
)

internal data class ScheduledGuidePlayback(
    val snapshot: GuidePlaybackSnapshot,
    val atMs: Long,
    val force: Boolean = true,
) {
    fun project(nowMs: Long): GuidePlaybackSnapshot =
        snapshot.copy(positionMs = GuideSessionTiming.position(snapshot, atMs, nowMs))
}

interface GuideSessionPlayback {
    fun snapshot(): GuidePlaybackSnapshot?
    fun isReady(): Boolean
    fun hasGuide(guideId: String): Boolean
    fun guideTitle(guideId: String): String?
    fun apply(snapshot: GuidePlaybackSnapshot)
    fun pause()
}

internal object GuideSessionTiming {
    const val MAX_GUIDE_ID_LENGTH = 128
    const val MAX_CHAPTER_INDEX = 512
    const val MAX_SUBCHAPTER_INDEX = 512

    // Same discipline as SessionTiming.MAX_POSITION_MS: a full day is the
    // largest position or duration a frame may carry.
    const val MAX_POSITION_MS = SessionTiming.MAX_POSITION_MS

    // True when the snapshot carries a real seekable position, i.e. it came
    // from audio-backed playback rather than the TTS relay.
    fun hasPosition(snapshot: GuidePlaybackSnapshot): Boolean = snapshot.durationMs > 0

    // Where the item position will be at nowMs for a snapshot taken at atMs:
    // it advances in real time while playing (guides play at 1x; there is no
    // speed control) and is clamped to the item's duration when known. A
    // snapshot carrying neither a position nor a duration (the TTS relay's
    // 0/0) has nothing to project: projecting one would fabricate a position
    // the speech engine cannot honour, so its position stays 0.
    fun position(snapshot: GuidePlaybackSnapshot, atMs: Long, nowMs: Long): Long {
        if (snapshot.positionMs == 0L && snapshot.durationMs == 0L) return 0
        val projected = snapshot.positionMs +
            if (snapshot.playing) (nowMs - atMs).coerceAtLeast(0) else 0
        val ceiling = if (snapshot.durationMs > 0) snapshot.durationMs else MAX_POSITION_MS
        return projected.coerceIn(0, ceiling)
    }

    fun valid(snapshot: GuidePlaybackSnapshot): Boolean =
        snapshot.guideId.isNotBlank() && snapshot.guideId.length <= MAX_GUIDE_ID_LENGTH &&
            snapshot.guideTitle.length <= 512 &&
            snapshot.chapterIndex in 0..MAX_CHAPTER_INDEX &&
            snapshot.subchapterIndex in -1..MAX_SUBCHAPTER_INDEX &&
            snapshot.positionMs in 0..MAX_POSITION_MS &&
            snapshot.durationMs in 0..MAX_POSITION_MS

    fun valid(command: GuidePlaybackCommand): Boolean = when (command.action) {
        "play", "pause", "stop" -> command.positionMs in 0..MAX_POSITION_MS
        "seek" -> command.positionMs in 0..MAX_POSITION_MS
        "jump" -> command.chapterIndex in 0..MAX_CHAPTER_INDEX &&
            command.subchapterIndex in -1..MAX_SUBCHAPTER_INDEX &&
            command.positionMs in 0..MAX_POSITION_MS
        else -> false
    }

    fun scheduled(current: GuidePlaybackSnapshot, command: GuidePlaybackCommand): GuidePlaybackSnapshot =
        scheduled(current, currentAtMs = 0, command, executeAtMs = 0)

    // Mirrors SessionTiming.scheduled: the current position is first projected
    // to the execution time, then the command is applied. A play/pause command
    // only overrides the position when it carries an explicit one
    // (positionMs > 0); with the default 0 the projected position stands, so a
    // plain resume never restarts the item.
    fun scheduled(
        current: GuidePlaybackSnapshot,
        currentAtMs: Long,
        command: GuidePlaybackCommand,
        executeAtMs: Long,
    ): GuidePlaybackSnapshot {
        require(valid(command))
        val projected = current.copy(positionMs = position(current, currentAtMs, executeAtMs))
        return when (command.action) {
            "play" -> projected.copy(
                playing = true,
                positionMs = if (command.positionMs > 0) command.positionMs else projected.positionMs,
            )
            "pause" -> projected.copy(
                playing = false,
                positionMs = if (command.positionMs > 0) command.positionMs else projected.positionMs,
            )
            "stop" -> projected.copy(playing = false, chapterIndex = 0, subchapterIndex = -1, positionMs = 0, durationMs = 0)
            "seek" -> projected.copy(positionMs = clampToItem(command.positionMs, projected.durationMs))
            "jump" -> {
                val sameItem = command.chapterIndex == projected.chapterIndex &&
                    command.subchapterIndex == projected.subchapterIndex
                val duration = if (sameItem) projected.durationMs else 0
                projected.copy(
                    chapterIndex = command.chapterIndex,
                    subchapterIndex = command.subchapterIndex,
                    playing = true,
                    positionMs = clampToItem(command.positionMs, duration),
                    durationMs = duration,
                )
            }
            else -> error("Unsupported command")
        }
    }

    private fun clampToItem(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceIn(0, MAX_POSITION_MS)
}

// Play/pause duplicate suppression for guide commands, mirroring
// SessionPlaybackDebouncer (which is typed on the tour PlaybackCommand).
internal class GuidePlaybackDebouncer {
    private data class AcceptedCommand(val source: String?, val atMs: Long)
    private var lastAccepted: AcceptedCommand? = null

    fun shouldAccept(command: GuidePlaybackCommand, playing: Boolean, source: String?, nowMs: Long, debounceMs: Long): Boolean {
        if (!isPlayPause(command) || command.positionMs > 0) return true
        if (playing == (command.action == "play")) return false
        if (debounceMs == 0L) return true
        val previous = lastAccepted ?: return true
        return source == previous.source || nowMs - previous.atMs > debounceMs
    }

    fun scheduled(command: GuidePlaybackCommand, source: String?, nowMs: Long) {
        if (!isPlayPause(command)) return
        lastAccepted = AcceptedCommand(source, nowMs)
    }

    fun reset() {
        lastAccepted = null
    }

    private fun isPlayPause(command: GuidePlaybackCommand): Boolean =
        command.action == "play" || command.action == "pause"
}
