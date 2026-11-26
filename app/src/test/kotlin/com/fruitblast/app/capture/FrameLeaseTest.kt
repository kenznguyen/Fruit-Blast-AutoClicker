package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLeaseTest {
    @Test
    fun exposesValueOnlyWhileLeaseIsOpen() {
        var releases = 0
        val lease = FrameLease("frame") { releases += 1 }

        assertEquals("frame", lease.useValue { it })
        assertFalse(lease.isClosed)
        assertEquals(0, releases)

        lease.close()

        assertTrue(lease.isClosed)
        assertEquals(1, releases)
    }

    @Test
    fun rejectsNewReadersAfterClose() {
        val lease = FrameLease("frame") {}

        lease.close()

        assertThrows(IllegalStateException::class.java) {
            lease.useValue { it }
        }
    }

    @Test
    fun closeIsIdempotentAndReleasesExactlyOnce() {
        var releases = 0
        val lease = FrameLease("frame") { releases += 1 }

        lease.close()
        lease.close()

        assertEquals(1, releases)
    }

    @Test
    fun defersReleaseUntilAnActiveReaderReturns() {
        var releases = 0
        val lease = FrameLease("frame") { releases += 1 }

        val value = lease.useValue {
            lease.close()
            assertTrue(lease.isClosed)
            assertEquals(0, releases)
            it
        }

        assertEquals("frame", value)
        assertEquals(1, releases)
    }

    @Test
    fun releasesAfterReaderThrowsWhenLeaseWasClosed() {
        var releases = 0
        val lease = FrameLease("frame") { releases += 1 }

        assertThrows(IllegalArgumentException::class.java) {
            lease.useValue {
                lease.close()
                throw IllegalArgumentException("consumer failure")
            }
        }

        assertEquals(1, releases)
    }
}
