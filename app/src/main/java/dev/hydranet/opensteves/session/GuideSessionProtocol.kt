package dev.hydranet.opensteves.session

// A guide session mirrors a tour session, but the shared content is a bundled
// custom guide identified by its guideId: both apps already carry identical
// guide content, so nothing is transferred. Playback state is a position in
// the guide (chapter / subchapter) plus a playing flag — text-to-speech cannot
// seek inside an utterance, so synchronisation is per subchapter and there is
// deliberately no position projection or drift correction like SessionTiming.
data class GuidePlaybackSnapshot(
    val guideId: String,
    val guideTitle: String = "",
    val chapterIndex: Int = 0,
    val subchapterIndex: Int = -1,
    val playing: Boolean = false,
)

data class GuidePlaybackCommand(
    val action: String,
    val chapterIndex: Int = 0,
    val subchapterIndex: Int = -1,
)

internal data class ScheduledGuidePlayback(val snapshot: GuidePlaybackSnapshot, val atMs: Long)

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

    fun valid(snapshot: GuidePlaybackSnapshot): Boolean =
        snapshot.guideId.isNotBlank() && snapshot.guideId.length <= MAX_GUIDE_ID_LENGTH &&
            snapshot.guideTitle.length <= 512 &&
            snapshot.chapterIndex in 0..MAX_CHAPTER_INDEX &&
            snapshot.subchapterIndex in -1..MAX_SUBCHAPTER_INDEX

    fun valid(command: GuidePlaybackCommand): Boolean = when (command.action) {
        "play", "pause", "stop" -> true
        "jump" -> command.chapterIndex in 0..MAX_CHAPTER_INDEX &&
            command.subchapterIndex in -1..MAX_SUBCHAPTER_INDEX
        else -> false
    }

    // "jump" lands on the requested item and starts speaking it, like tapping
    // its play button; "stop" parks everyone back at the start of the guide.
    fun scheduled(current: GuidePlaybackSnapshot, command: GuidePlaybackCommand): GuidePlaybackSnapshot {
        require(valid(command))
        return when (command.action) {
            "play" -> current.copy(playing = true)
            "pause" -> current.copy(playing = false)
            "stop" -> current.copy(playing = false, chapterIndex = 0, subchapterIndex = -1)
            "jump" -> current.copy(
                chapterIndex = command.chapterIndex,
                subchapterIndex = command.subchapterIndex,
                playing = true,
            )
            else -> error("Unsupported command")
        }
    }
}
