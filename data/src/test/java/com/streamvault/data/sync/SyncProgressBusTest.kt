package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Test

class SyncProgressBusTest {
    private fun progress(section: Section = Section.LIVE) = SyncProgress(section, 3, 10, "Sport", 42)

    @Test
    fun finishingOneProvider_doesNotClearAnotherProvidersProgress() {
        val bus = SyncProgressBus()
        val providerA = bus.begin(1L)
        val providerB = bus.begin(2L)
        bus.emit(providerA, progress())
        val providerBProgress = progress(Section.VOD)
        bus.emit(providerB, providerBProgress)

        bus.finish(providerA)

        assertThat(bus.progressByProvider.value).containsKey(2L)
        assertThat(bus.progressByProvider.value[2L]?.progress).isEqualTo(providerBProgress)
        assertThat(bus.aggregate.value?.activeProviderCount).isEqualTo(1)
    }

    @Test
    fun staleSession_cannotClearOrPublishOverReplacementSession() {
        val bus = SyncProgressBus()
        val stale = bus.begin(1L)
        bus.emit(stale, progress())
        val replacement = bus.begin(1L)
        val replacementProgress = progress(Section.SERIES)
        bus.emit(replacement, replacementProgress)

        bus.emit(stale, progress(Section.VOD))
        bus.finish(stale)

        assertThat(bus.progressByProvider.value[1L]?.session).isEqualTo(replacement)
        assertThat(bus.progressByProvider.value[1L]?.progress).isEqualTo(replacementProgress)
        assertThat(replacement.epoch).isGreaterThan(stale.epoch)
    }

    @Test
    fun aggregate_isDerivedFromAllActiveProviders() {
        val bus = SyncProgressBus()
        val providerA = bus.begin(1L)
        val providerB = bus.begin(2L)
        bus.emit(providerA, progress())
        bus.emit(providerB, progress(Section.VOD))

        assertThat(bus.aggregate.value?.activeProviderCount).isEqualTo(2)
        assertThat(bus.aggregate.value?.representative?.session).isEqualTo(providerB)
    }

    @Test
    fun cancellingOneProvider_finishesOnlyItsOwnSession() {
        val bus = SyncProgressBus()
        val cancelledProvider = bus.begin(1L)
        val activeProvider = bus.begin(2L)
        val activeProgress = progress(Section.SERIES)
        bus.emit(cancelledProvider, progress())
        bus.emit(activeProvider, activeProgress)

        try {
            throw CancellationException("manual sync cancelled")
        } catch (_: CancellationException) {
            // Mirrors a sync entry point's cancellation path.
        } finally {
            bus.finish(cancelledProvider)
        }

        assertThat(bus.progressByProvider.value.keys).containsExactly(2L)
        assertThat(bus.progressByProvider.value[2L]?.progress).isEqualTo(activeProgress)
        assertThat(bus.aggregate.value?.representative?.session).isEqualTo(activeProvider)
    }

    @Test
    fun concurrentManualAndBackgroundSessions_remainProviderScoped() {
        val bus = SyncProgressBus()
        val executor = Executors.newFixedThreadPool(2)
        val manualPublished = CountDownLatch(1)
        val backgroundPublished = CountDownLatch(1)
        val manualFinished = CountDownLatch(1)

        try {
            val manual = executor.submit {
                val session = bus.begin(1L)
                bus.emit(session, progress(Section.LIVE))
                manualPublished.countDown()
                check(backgroundPublished.await(5, TimeUnit.SECONDS))
                bus.finish(session)
                manualFinished.countDown()
            }
            val background = executor.submit(Callable {
                check(manualPublished.await(5, TimeUnit.SECONDS))
                val session = bus.begin(2L)
                bus.emit(session, progress(Section.VOD))
                backgroundPublished.countDown()
                check(manualFinished.await(5, TimeUnit.SECONDS))
                session
            })

            manual.get(5, TimeUnit.SECONDS)
            val backgroundSession = background.get(5, TimeUnit.SECONDS)

            assertThat(bus.progressByProvider.value.keys).containsExactly(2L)
            assertThat(bus.progressByProvider.value[2L]?.session).isEqualTo(backgroundSession)
            assertThat(bus.aggregate.value?.activeProviderCount).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }
}
