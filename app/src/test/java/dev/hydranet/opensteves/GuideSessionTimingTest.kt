package dev.hydranet.opensteves

import dev.hydranet.opensteves.session.GuidePlaybackCommand
import dev.hydranet.opensteves.session.GuidePlaybackSnapshot
import dev.hydranet.opensteves.session.GuideSessionTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSessionTimingTest {
    private val atStart = GuidePlaybackSnapshot("tower-of-london", "Tower of London", 0, -1, playing = false)
    private val midGuide = GuidePlaybackSnapshot("tower-of-london", "Tower of London", 3, 2, playing = true)

    @Test
    fun snapshotValidationAcceptsGuidePositions() {
        assertTrue(GuideSessionTiming.valid(atStart))
        assertTrue(GuideSessionTiming.valid(midGuide))
        assertTrue(GuideSessionTiming.valid(midGuide.copy(subchapterIndex = -1)))
    }

    @Test
    fun snapshotValidationRejectsMalformedSnapshots() {
        assertFalse(GuideSessionTiming.valid(atStart.copy(guideId = "")))
        assertFalse(GuideSessionTiming.valid(atStart.copy(guideId = "x".repeat(GuideSessionTiming.MAX_GUIDE_ID_LENGTH + 1))))
        assertFalse(GuideSessionTiming.valid(atStart.copy(chapterIndex = -1)))
        assertFalse(GuideSessionTiming.valid(atStart.copy(chapterIndex = GuideSessionTiming.MAX_CHAPTER_INDEX + 1)))
        assertFalse(GuideSessionTiming.valid(atStart.copy(subchapterIndex = -2)))
        assertFalse(GuideSessionTiming.valid(atStart.copy(subchapterIndex = GuideSessionTiming.MAX_SUBCHAPTER_INDEX + 1)))
    }

    @Test
    fun commandValidationCoversTransportAndJump() {
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("play")))
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("pause")))
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("stop")))
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("jump", 2, 0)))
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("jump", 2, -1)))
        assertTrue(GuideSessionTiming.valid(GuidePlaybackCommand("seek", 2, 0, positionMs = 5_000)))
        assertFalse(GuideSessionTiming.valid(GuidePlaybackCommand("speed", 2, 0)))
        assertFalse(GuideSessionTiming.valid(GuidePlaybackCommand("jump", -1, 0)))
        assertFalse(GuideSessionTiming.valid(GuidePlaybackCommand("jump", 0, -2)))
    }

    @Test
    fun playAndPauseKeepThePosition() {
        assertEquals(midGuide, GuideSessionTiming.scheduled(midGuide, GuidePlaybackCommand("play")))
        assertEquals(midGuide.copy(playing = false), GuideSessionTiming.scheduled(midGuide, GuidePlaybackCommand("pause")))
        assertEquals(atStart.copy(playing = true), GuideSessionTiming.scheduled(atStart, GuidePlaybackCommand("play")))
    }

    @Test
    fun jumpLandsOnTheItemAndPlays() {
        assertEquals(
            midGuide.copy(chapterIndex = 5, subchapterIndex = 1, playing = true),
            GuideSessionTiming.scheduled(midGuide.copy(playing = false), GuidePlaybackCommand("jump", 5, 1)),
        )
        assertEquals(
            midGuide.copy(chapterIndex = 2, subchapterIndex = -1, playing = true),
            GuideSessionTiming.scheduled(midGuide, GuidePlaybackCommand("jump", 2, -1)),
        )
    }

    @Test
    fun stopParksEveryoneAtTheStart() {
        assertEquals(atStart, GuideSessionTiming.scheduled(midGuide, GuidePlaybackCommand("stop")))
    }
}
