package dev.hydranet.opensteves

import dev.hydranet.opensteves.session.GuidePlaybackCommand
import dev.hydranet.opensteves.session.GuidePlaybackDebouncer
import dev.hydranet.opensteves.session.GuidePlaybackSnapshot
import dev.hydranet.opensteves.session.GuideSessionTiming
import dev.hydranet.opensteves.session.SessionDebugSettings
import dev.hydranet.opensteves.session.SessionDriftCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSessionSyncTest {
    // An audio-backed item: chapter 3, subchapter 2, 90 seconds long.
    private val audioItem = GuidePlaybackSnapshot(
        "tower-of-london", "Tower of London", 3, 2,
        playing = true, positionMs = 10_000, durationMs = 90_000,
    )
    private val ttsItem = GuidePlaybackSnapshot("tower-of-london", "Tower of London", 3, 2, playing = true)

    @Test
    fun projectionAdvancesWhilePlayingAndStaysPutWhilePaused() {
        assertEquals(15_000, GuideSessionTiming.position(audioItem, atMs = 1_000, nowMs = 6_000))
        assertEquals(10_000, GuideSessionTiming.position(audioItem.copy(playing = false), atMs = 1_000, nowMs = 6_000))
        // A clock reading from before the snapshot never projects backwards.
        assertEquals(10_000, GuideSessionTiming.position(audioItem, atMs = 6_000, nowMs = 1_000))
    }

    @Test
    fun projectionClampsToDurationWhenKnown() {
        assertEquals(90_000, GuideSessionTiming.position(audioItem, atMs = 0, nowMs = 10_000_000))
        // Without a duration the only ceiling is the day-long maximum.
        val unknown = audioItem.copy(durationMs = 0, positionMs = GuideSessionTiming.MAX_POSITION_MS - 10)
        assertEquals(GuideSessionTiming.MAX_POSITION_MS, GuideSessionTiming.position(unknown, atMs = 0, nowMs = 10_000))
    }

    @Test
    fun ttsSnapshotsHaveNoPositionToProject() {
        assertFalse(GuideSessionTiming.hasPosition(ttsItem))
        assertTrue(GuideSessionTiming.hasPosition(audioItem))
        assertEquals(0, GuideSessionTiming.position(ttsItem, atMs = 0, nowMs = 60_000))
    }

    @Test
    fun positionValidityMirrorsTheTourDiscipline() {
        assertTrue(GuideSessionTiming.valid(audioItem))
        assertFalse(GuideSessionTiming.valid(audioItem.copy(positionMs = -1)))
        assertFalse(GuideSessionTiming.valid(audioItem.copy(positionMs = GuideSessionTiming.MAX_POSITION_MS + 1)))
        assertFalse(GuideSessionTiming.valid(audioItem.copy(durationMs = -1)))
        assertFalse(GuideSessionTiming.valid(audioItem.copy(durationMs = GuideSessionTiming.MAX_POSITION_MS + 1)))
        assertFalse(GuideSessionTiming.valid(GuidePlaybackCommand("seek", positionMs = -1)))
        assertFalse(GuideSessionTiming.valid(GuidePlaybackCommand("seek", positionMs = GuideSessionTiming.MAX_POSITION_MS + 1)))
        assertFalse(GuideSessionTiming.valid(GuidePlaybackCommand("play", positionMs = GuideSessionTiming.MAX_POSITION_MS + 1)))
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("seek", positionMs = GuideSessionTiming.MAX_POSITION_MS)))
    }

    @Test
    fun scheduledSeekMovesWithinTheItemAndKeepsPlayingState() {
        val seeked = GuideSessionTiming.scheduled(audioItem, GuidePlaybackCommand("seek", positionMs = 42_000))
        assertEquals(audioItem.copy(positionMs = 42_000), seeked)
        val paused = GuideSessionTiming.scheduled(audioItem.copy(playing = false), GuidePlaybackCommand("seek", positionMs = 42_000))
        assertFalse(paused.playing)
        assertEquals(42_000, paused.positionMs)
        // A seek past the end lands on the end, not beyond it.
        assertEquals(90_000, GuideSessionTiming.scheduled(audioItem, GuidePlaybackCommand("seek", positionMs = 900_000)).positionMs)
    }

    @Test
    fun scheduledPlayPauseProjectToExecutionTime() {
        // Pausing five seconds after the snapshot freezes the projected spot.
        val paused = GuideSessionTiming.scheduled(audioItem, currentAtMs = 1_000, GuidePlaybackCommand("pause"), executeAtMs = 6_000)
        assertEquals(audioItem.copy(playing = false, positionMs = 15_000), paused)
        // Resuming carries no position, so the paused spot stands.
        val resumed = GuideSessionTiming.scheduled(paused, currentAtMs = 6_000, GuidePlaybackCommand("play"), executeAtMs = 9_000)
        assertEquals(paused.copy(playing = true), resumed)
    }

    @Test
    fun scheduledPlayPauseHonorAnExplicitPosition() {
        val play = GuideSessionTiming.scheduled(audioItem.copy(playing = false), GuidePlaybackCommand("play", positionMs = 30_000))
        assertTrue(play.playing)
        assertEquals(30_000, play.positionMs)
        val pause = GuideSessionTiming.scheduled(audioItem, GuidePlaybackCommand("pause", positionMs = 30_000))
        assertFalse(pause.playing)
        assertEquals(30_000, pause.positionMs)
    }

    @Test
    fun scheduledJumpCarriesOffsetAndDropsStaleDuration() {
        val jumped = GuideSessionTiming.scheduled(audioItem, GuidePlaybackCommand("jump", 5, 1, positionMs = 7_500))
        assertEquals(5, jumped.chapterIndex)
        assertEquals(1, jumped.subchapterIndex)
        assertTrue(jumped.playing)
        assertEquals(7_500, jumped.positionMs)
        // The new item's duration is unknown until the player reports it.
        assertEquals(0, jumped.durationMs)
        // Jumping within the same item keeps its duration and clamps to it.
        val sameItem = GuideSessionTiming.scheduled(audioItem, GuidePlaybackCommand("jump", 3, 2, positionMs = 900_000))
        assertEquals(90_000, sameItem.durationMs)
        assertEquals(90_000, sameItem.positionMs)
    }

    @Test
    fun scheduledStopResetsPositionAndDuration() {
        val stopped = GuideSessionTiming.scheduled(audioItem, GuidePlaybackCommand("stop"))
        assertEquals(
            GuidePlaybackSnapshot("tower-of-london", "Tower of London", 0, -1, playing = false),
            stopped,
        )
    }

    @Test
    fun driftCorrectionNeedsRepeatedLargeDriftWhilePlaying() {
        val settings = SessionDebugSettings()
        val correction = SessionDriftCorrection()
        // Small drift never corrects, and resets the sample count.
        assertFalse(correction.shouldCorrect(500, playing = true, nowMs = 0, settings = settings))
        // Large drift must repeat for the configured number of samples.
        assertFalse(correction.shouldCorrect(5_000, playing = true, nowMs = 0, settings = settings))
        assertFalse(correction.shouldCorrect(5_000, playing = true, nowMs = 1_000, settings = settings))
        assertTrue(correction.shouldCorrect(5_000, playing = true, nowMs = 2_000, settings = settings))
        correction.positionApplied(nowMs = 2_000)
        // The cooldown suppresses an immediate second correction.
        assertFalse(correction.shouldCorrect(5_000, playing = true, nowMs = 3_000, settings = settings))
        assertFalse(correction.shouldCorrect(5_000, playing = true, nowMs = 4_000, settings = settings))
        assertFalse(correction.shouldCorrect(5_000, playing = true, nowMs = 5_000, settings = settings))
    }

    @Test
    fun driftCorrectionSnapsImmediatelyWhenPausedBeyondTolerance() {
        val settings = SessionDebugSettings()
        val correction = SessionDriftCorrection()
        assertFalse(correction.shouldCorrect(500, playing = false, nowMs = 0, settings = settings))
        assertTrue(correction.shouldCorrect(501, playing = false, nowMs = 0, settings = settings))
        assertTrue(correction.shouldCorrect(-5_000, playing = false, nowMs = 0, settings = settings))
    }

    @Test
    fun debouncerDropsDuplicateAndCrossSourcePlayPause() {
        val debouncer = GuidePlaybackDebouncer()
        // Already playing: another plain play is a duplicate, not a command.
        assertFalse(debouncer.shouldAccept(GuidePlaybackCommand("play"), playing = true, source = null, nowMs = 0, debounceMs = 2_000))
        assertTrue(debouncer.shouldAccept(GuidePlaybackCommand("pause"), playing = true, source = "host", nowMs = 0, debounceMs = 2_000))
        debouncer.scheduled(GuidePlaybackCommand("pause"), source = "host", nowMs = 0)
        // The same source may flip straight back; another source must wait.
        assertTrue(debouncer.shouldAccept(GuidePlaybackCommand("play"), playing = false, source = "host", nowMs = 100, debounceMs = 2_000))
        assertFalse(debouncer.shouldAccept(GuidePlaybackCommand("play"), playing = false, source = "guest", nowMs = 100, debounceMs = 2_000))
        assertTrue(debouncer.shouldAccept(GuidePlaybackCommand("play"), playing = false, source = "guest", nowMs = 2_101, debounceMs = 2_000))
        // Seeks, jumps and positioned plays are never debounced away.
        assertTrue(debouncer.shouldAccept(GuidePlaybackCommand("seek", positionMs = 1_000), playing = false, source = "guest", nowMs = 100, debounceMs = 2_000))
        assertTrue(debouncer.shouldAccept(GuidePlaybackCommand("play", positionMs = 1_000), playing = true, source = "guest", nowMs = 100, debounceMs = 2_000))
    }
}
