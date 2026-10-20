package app.scry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class BackupLogicTest {
    private fun item(id: Long, added: Long, name: String = "IMG_$id.jpg", size: Long = 100) =
        MediaItem(id, false, name, "image/jpeg", size, added)

    @Test
    fun watermarkAdvancesOnlyThroughAnUnbrokenRun() {
        val t = BackupTracker(watermark = 1_000)
        t.succeeded(item(1, 2_000))
        t.succeeded(item(2, 3_000))
        assertEquals(3_000, t.watermark)

        t.failed(item(3, 4_000)) // first failure holds the mark back
        t.succeeded(item(4, 5_000)) // later successes must not move it past the failure
        assertEquals(3_000, t.watermark)
        assertEquals(2, t.uploaded - 1) // two before the failure, one after: three in all
    }

    @Test
    fun successfulItemsAreNotUploadedAgainInTheOverlapWindow() {
        val t = BackupTracker(watermark = 100_000)
        val a = item(1, 99_000)
        assertTrue(t.needs(a))
        t.succeeded(a)
        assertFalse("already uploaded, even though it is inside the overlap", t.needs(a))
        assertEquals(100_000 - BackupTracker.OVERLAP_SECONDS, t.queryFloor())
    }

    @Test
    fun anItemThatKeepsFailingIsEventuallyAbandonedSoTheMarkCanMove() {
        val bad = item(7, 5_000)
        val t = BackupTracker(watermark = 1_000)
        assertFalse(t.failed(bad))
        assertFalse(t.failed(bad))
        assertTrue("third failed run gives up on it", t.failed(bad))
        assertFalse("and it is not retried", t.needs(bad))
        assertEquals(1, t.abandoned)
    }

    @Test
    fun anAbandonedItemDoesNotHoldBackLaterOnes() {
        val bad = item(7, 5_000)
        val t = BackupTracker(watermark = 1_000, attempts = mutableMapOf(7L to 2))
        assertTrue(t.failed(bad))
        t.succeeded(item(8, 6_000))
        assertEquals(6_000, t.watermark)
    }

    @Test
    fun pruningForgetsOnlyItemsOlderThanTheWindow() {
        val t = BackupTracker(watermark = 200_000)
        val old = item(1, 10_000)
        val recent = item(2, 199_000)
        t.succeeded(old)
        t.succeeded(recent)
        t.prune()
        assertTrue("outside the window, can never be queried again", old.id !in t.doneSnapshot())
        assertTrue(recent.id in t.doneSnapshot())
    }

    @Test
    fun filesWithTheSameNameNeverShareABatch() {
        val b = BatchPlanner(maxFiles = 10)
        val first = item(1, 1, name = "IMG_0001.jpg")
        val clash = item(2, 2, name = "IMG_0001.jpg") // same name from another folder
        b.add(first)
        assertTrue(b.mustFlushBefore(clash))
        b.clear()
        assertFalse(b.mustFlushBefore(clash))
    }

    @Test
    fun batchesAreCappedByCountAndSize() {
        val b = BatchPlanner(maxFiles = 2, maxBytes = 250)
        b.add(item(1, 1, size = 100))
        b.add(item(2, 2, size = 100))
        assertTrue("count cap", b.mustFlushBefore(item(3, 3, size = 10)))
        val c = BatchPlanner(maxFiles = 10, maxBytes = 250)
        c.add(item(1, 1, size = 200))
        assertTrue("size cap", c.mustFlushBefore(item(2, 2, size = 100)))
    }

    @Test
    fun multipartLengthMatchesTheBytesActuallyWritten() {
        val data1 = ByteArray(1_000) { it.toByte() }
        val data2 = "hello".toByteArray()
        val body = MultipartBody(
            "XXBOUNDARYXX",
            listOf(
                MultipartBody.Part("files", "a \"quoted\".jpg", "image/jpeg", data1.size.toLong()) { ByteArrayInputStream(data1) },
                MultipartBody.Part("files", "b.txt", "text/plain", data2.size.toLong()) { ByteArrayInputStream(data2) },
            ),
        )
        val out = ByteArrayOutputStream()
        body.writeTo(out)
        assertEquals(body.contentLength, out.size().toLong())
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("--XXBOUNDARYXX\r\nContent-Disposition: form-data; name=\"files\"; filename=\"a %22quoted%22.jpg\""))
        assertTrue(text.endsWith("\r\n--XXBOUNDARYXX--\r\n"))
    }

    @Test(expected = IOException::class)
    fun aFileThatYieldsFewerBytesThanDeclaredFailsInsteadOfCorruptingTheRequest() {
        val body = MultipartBody(
            "B", listOf(MultipartBody.Part("files", "x.jpg", "image/jpeg", 10) { ByteArrayInputStream(ByteArray(4)) })
        )
        body.writeTo(ByteArrayOutputStream())
    }

    @Test(expected = IOException::class)
    fun aFileThatYieldsMoreBytesThanDeclaredFails() {
        val body = MultipartBody(
            "B", listOf(MultipartBody.Part("files", "x.jpg", "image/jpeg", 4) { ByteArrayInputStream(ByteArray(10)) })
        )
        body.writeTo(ByteArrayOutputStream())
    }

    @Test
    fun headerInjectionInFileNamesIsNeutralised() {
        assertEquals("evil.jpgX-Injected: 1", MultipartBody.escape("evil.jpg\r\nX-Injected: 1"))
    }
}
