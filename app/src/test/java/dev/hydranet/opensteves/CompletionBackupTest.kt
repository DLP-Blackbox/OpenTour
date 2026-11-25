package dev.hydranet.opensteves

import dev.hydranet.opensteves.data.CompletionStore
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionBackupTest {
    @Test
    fun backupRoundTripsKeySet() {
        val keys = setOf(
            CompletionStore.guideSubchapterKey("tower-of-london", 0, 1),
            CompletionStore.guideSubchapterKey("tower-of-london", 2, 0),
            CompletionStore.tourChapterKey(42, 1_234_000L),
        )
        assertEquals(keys, CompletionStore.decodeBackup(CompletionStore.encodeBackup(keys)))
    }

    @Test
    fun emptySetRoundTrips() {
        assertEquals("[]", CompletionStore.encodeBackup(emptySet()))
        assertTrue(CompletionStore.decodeBackup("[]").isEmpty())
    }

    @Test
    fun corruptBackupThrowsSoCallersCanIgnoreIt() {
        assertThrows(JSONException::class.java) { CompletionStore.decodeBackup("{not a backup") }
        assertThrows(JSONException::class.java) { CompletionStore.decodeBackup("") }
    }
}
