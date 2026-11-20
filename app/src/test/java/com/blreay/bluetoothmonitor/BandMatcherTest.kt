package com.blreay.bluetoothmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band's advertised name ends in 35BA, which is the last two octets of
 * its MAC address. That makes the MAC the more reliable key: BLE advertising
 * truncates long names and may carry the name in the scan response instead of
 * the primary advertisement.
 */
class BandMatcherTest {

    private val bandMac = "AA:BB:CC:DD:35:BA"
    private val unrelatedMac = "AA:BB:CC:DD:11:22"

    @Test
    fun `full advertised name matches`() {
        assertTrue(BandMatcher.isTarget(bandMac, "Xiaomi Smart Band 8 35BA"))
    }

    @Test
    fun `full name matches even when the mac is unrelated`() {
        assertTrue(BandMatcher.isTarget(unrelatedMac, "Xiaomi Smart Band 8 35BA"))
    }

    @Test
    fun `name truncated by advertising still matches via mac suffix`() {
        assertTrue(BandMatcher.isTarget(bandMac, "Xiaomi Smart Band 8"))
    }

    @Test
    fun `name carried only in scan response still matches via mac suffix`() {
        assertTrue(BandMatcher.isTarget(bandMac, null))
    }

    @Test
    fun `lowercase mac still matches`() {
        assertTrue(BandMatcher.isTarget("aa:bb:cc:dd:35:ba", "Xiaomi Smart Band 8"))
    }

    @Test
    fun `a different band does not match`() {
        assertFalse(BandMatcher.isTarget(unrelatedMac, "Xiaomi Smart Band 7 1122"))
    }

    @Test
    fun `same mac suffix but a different product line does not match`() {
        assertFalse(BandMatcher.isTarget(bandMac, "Mi Wireless Earbuds"))
    }

    @Test
    fun `an unrelated named device does not match`() {
        assertFalse(BandMatcher.isTarget(unrelatedMac, "Some Random Speaker"))
    }

    @Test
    fun `an unrelated anonymous device does not match`() {
        assertFalse(BandMatcher.isTarget(unrelatedMac, null))
    }

    @Test
    fun `no information at all does not match`() {
        assertFalse(BandMatcher.isTarget(null, null))
    }
}
