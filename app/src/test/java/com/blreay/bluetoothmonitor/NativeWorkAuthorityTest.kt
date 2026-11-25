package com.blreay.bluetoothmonitor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeWorkAuthorityTest {
    @Test fun `queued native work checks lease again at actual worker entry`() {
        val pool = Executors.newSingleThreadExecutor()
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lease = CommandLease.foreground()
        val authority = NativeWorkAuthority()
        try {
            pool.execute { occupied.countDown(); release.await() }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))
            var invocations = 0
            val queued = pool.submit { if (authority.enter(lease.valid())) invocations++ }
            lease.cancel()
            release.countDown()
            queued.get(1, TimeUnit.SECONDS)
            assertEquals(0, invocations)
            assertTrue(authority.expireBeforeEntry())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `timeout entered work is uncertainty not proof of cancellation or return`() {
        val authority = NativeWorkAuthority()
        assertTrue(authority.enter(true))
        assertFalse(authority.expireBeforeEntry())
        assertFalse(authority.hasReturned())
        authority.returned()
        assertTrue(authority.hasReturned())
        assertFalse(authority.enter(true))
    }

    @Test fun `timeout wins queued admission and cannot later submit even if lease remains valid`() {
        val authority = NativeWorkAuthority()
        assertTrue(authority.expireBeforeEntry())
        assertFalse(authority.enter(true))
        assertFalse(authority.hasReturned())
    }
}
