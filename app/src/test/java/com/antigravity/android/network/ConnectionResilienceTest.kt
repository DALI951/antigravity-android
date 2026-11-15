package com.antigravity.android.network

import com.antigravity.android.data.network.BackoffCalculator
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.data.network.OfflineBannerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionResilienceTest {

    private val backoff = BackoffCalculator()

    // ==========================================
    // Tier 1: Happy-Path Backoff & State Tests (F11)
    // ==========================================

    @Test
    fun testInitialBackoffDelay_is500ms() {
        val delay = backoff.calculateDelay(attempt = 1)
        assertEquals(500L, delay)
    }

    @Test
    fun testExponentialBackoff_progressionWith1Point5Multiplier() {
        val delay1 = backoff.calculateDelay(attempt = 1) // 500
        val delay2 = backoff.calculateDelay(attempt = 2) // 500 * 1.5 = 750
        val delay3 = backoff.calculateDelay(attempt = 3) // 750 * 1.5 = 1125
        val delay4 = backoff.calculateDelay(attempt = 4) // 1125 * 1.5 = 1687

        assertEquals(500L, delay1)
        assertEquals(750L, delay2)
        assertEquals(1125L, delay3)
        assertEquals(1687L, delay4)
    }

    @Test
    fun testBackoffCappedAt30000ms() {
        val delayAttempt10 = backoff.calculateDelay(attempt = 10)
        val delayAttempt20 = backoff.calculateDelay(attempt = 20)
        val delayAttempt50 = backoff.calculateDelay(attempt = 50)

        assertTrue(delayAttempt10 <= 30000L)
        assertEquals(30000L, delayAttempt20)
        assertEquals(30000L, delayAttempt50)
    }

    @Test
    fun testConnectionStatusTransitions() {
        var status: ConnectionStatus = ConnectionStatus.Connected("http://127.0.0.1:49784")
        assertTrue(status is ConnectionStatus.Connected)

        // Socket drops
        status = ConnectionStatus.Reconnecting(attempt = 1, delayMs = backoff.calculateDelay(1))
        assertEquals(1, (status as ConnectionStatus.Reconnecting).attempt)
        assertEquals(500L, (status as ConnectionStatus.Reconnecting).delayMs)

        // Reconnect failure
        status = ConnectionStatus.Disconnected("Host unreachable")
        assertEquals("Host unreachable", (status as ConnectionStatus.Disconnected).reason)
    }

    @Test
    fun testOfflineBannerVisibility() {
        val connectedBanner = OfflineBannerState(ConnectionStatus.Connected("http://127.0.0.1:49784"))
        assertFalse("Banner must be hidden when connected", connectedBanner.isVisible)
        assertEquals("", connectedBanner.message)

        val reconnectingBanner = OfflineBannerState(ConnectionStatus.Reconnecting(attempt = 2, delayMs = 750L))
        assertTrue("Banner must be visible when reconnecting", reconnectingBanner.isVisible)
        assertTrue(reconnectingBanner.message.contains("attempt 2"))

        val disconnectedBanner = OfflineBannerState(ConnectionStatus.Disconnected("Network lost"))
        assertTrue("Banner must be visible when disconnected", disconnectedBanner.isVisible)
        assertTrue(disconnectedBanner.message.contains("No connection"))
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testAttemptZeroOrNegative_handledGracefully() {
        val delay0 = backoff.calculateDelay(0)
        val delayNeg = backoff.calculateDelay(-1)

        assertEquals(500L, delay0)
        assertEquals(500L, delayNeg)
    }

    @Test
    fun testCustomBackoffConfiguration() {
        val customBackoff = BackoffCalculator(initialDelayMs = 1000L, multiplier = 2.0, maxDelayMs = 10000L)
        assertEquals(1000L, customBackoff.calculateDelay(1))
        assertEquals(2000L, customBackoff.calculateDelay(2))
        assertEquals(4000L, customBackoff.calculateDelay(3))
        assertEquals(8000L, customBackoff.calculateDelay(4))
        assertEquals(10000L, customBackoff.calculateDelay(5))
    }

    @Test
    fun testFlappingConnectionStress() {
        var status: ConnectionStatus
        for (i in 1..100) {
            status = if (i % 2 == 0) {
                ConnectionStatus.Connected("http://localhost")
            } else {
                ConnectionStatus.Reconnecting(attempt = i, delayMs = backoff.calculateDelay(i))
            }
            val banner = OfflineBannerState(status)
            if (i % 2 == 0) {
                assertFalse(banner.isVisible)
            } else {
                assertTrue(banner.isVisible)
            }
        }
    }
}
