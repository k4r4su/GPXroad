package com.olivier.gpxroad.android.recording

import com.olivier.gpxroad.shared.recording.RecordedPoint
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Journal de l'enregistrement et « Sorties non enregistrées » : ce qui survit à un arrêt de l'app. */
class RecordingStorageTest {
    private val directory: File = Files.createTempDirectory("recording").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun journalRestoresEveryPointAndIgnoresATruncatedLine() {
        val journal = RecordingJournal(File(directory, "RideRecording"))
        journal.begin("SESSION", 1_000)
        journal.append(RecordedPoint(47.1, 7.1, 300.5, 2_000))
        journal.append(RecordedPoint(47.2, 7.2, null, 3_000))
        // Arrêt brutal pendant l'écriture d'un point.
        File(directory, "RideRecording/journal.jsonl").appendText("{\"lat\":47.3,\"lo")

        val restored = RecordingJournal(File(directory, "RideRecording")).load()!!
        assertEquals("SESSION", restored.sessionId)
        assertEquals(1_000, restored.startedMillis)
        assertEquals(listOf(RecordedPoint(47.1, 7.1, 300.5, 2_000), RecordedPoint(47.2, 7.2, null, 3_000)), restored.points)

        journal.clear()
        assertNull(journal.load())
    }

    @Test
    fun unsavedRidesAreRewrittenInPlaceAndTheOldestArePurged() {
        val store = UnsavedRideStore(File(directory, "UnsavedRides"))
        store.checkpoint("A", 1_000, "<gpx>a10</gpx>", 10, maxRetained = 2)
        store.checkpoint("A", 1_000, "<gpx>a20</gpx>", 20, maxRetained = 2)
        assertEquals(1, store.rides.size, "même sortie : réécrite, pas dupliquée")
        assertEquals(20, store.rides.single().pointCount)
        assertEquals("<gpx>a20</gpx>", store.file(store.rides.single()).readText())

        store.checkpoint("B", 2_000, "<gpx>b</gpx>", 10, maxRetained = 2)
        store.checkpoint("C", 3_000, "<gpx>c</gpx>", 10, maxRetained = 2)
        assertEquals(listOf("B", "C"), store.rides.map { it.id }.sorted(), "la plus ancienne est supprimée")
        assertTrue(!File(directory, "UnsavedRides/A.gpx").exists())

        // Relu au prochain lancement.
        assertEquals(listOf("B", "C"), UnsavedRideStore(File(directory, "UnsavedRides")).rides.map { it.id }.sorted())
        store.discard("B")
        assertEquals(listOf("C"), UnsavedRideStore(File(directory, "UnsavedRides")).rides.map { it.id })
    }
}
