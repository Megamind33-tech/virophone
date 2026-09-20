package com.viroreach.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSettingsTest {
    @Test
    fun `what arrives by itself, out of the box`() {
        val defaults = MediaPreferences()
        // Photos come in either way: a chat where pictures never appear feels broken.
        assertTrue(shouldAutoDownload(AutoKind.PHOTO, metered = true, defaults))
        assertTrue(shouldAutoDownload(AutoKind.PHOTO, metered = false, defaults))
        // GIFs are far heavier, so they wait for Wi-Fi.
        assertFalse(shouldAutoDownload(AutoKind.GIF, metered = true, defaults))
        assertTrue(shouldAutoDownload(AutoKind.GIF, metered = false, defaults))
    }

    @Test
    fun `turning photos off on mobile data leaves Wi-Fi alone`() {
        val saving = MediaPreferences(photosOnMobile = false)
        assertFalse(shouldAutoDownload(AutoKind.PHOTO, metered = true, saving))
        assertTrue(shouldAutoDownload(AutoKind.PHOTO, metered = false, saving))
    }

    @Test
    fun `everything can be turned off`() {
        val off = MediaPreferences(photosOnMobile = false, photosOnWifi = false, gifsOnMobile = false, gifsOnWifi = false)
        for (kind in AutoKind.entries) {
            for (metered in listOf(true, false)) {
                assertFalse("$kind metered=$metered", shouldAutoDownload(kind, metered, off))
            }
        }
    }

    @Test
    fun `storage sizes read the way people say them`() {
        assertEquals("0 MB", formatStorageSize(0))
        assertEquals("0 MB", formatStorageSize(-5))
        assertEquals("48 KB", formatStorageSize(50_000))
        assertEquals("0.5 MB", formatStorageSize(524_288))
        assertEquals("8.4 MB", formatStorageSize(8_800_000))
        assertEquals("340 MB", formatStorageSize(356_515_840))
        assertEquals("1.2 GB", formatStorageSize(1_288_490_189))
    }

    @Test
    fun `the bar splits by share, and never divides by zero`() {
        assertEquals(0f, storageShare(10, 0), 0.0001f)
        assertEquals(0.25f, storageShare(25, 100), 0.0001f)
        assertEquals(1f, storageShare(100, 100), 0.0001f)
    }
}
