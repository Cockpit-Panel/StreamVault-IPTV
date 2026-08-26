package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderWorkLockRegistryTest {
    @Test
    fun `same provider phases are serialized`() = runTest {
        val registry = ProviderWorkLockRegistry()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            registry.withProviderLock(7L) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = async {
            registry.withProviderLock(7L) {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()

        assertThat(secondEntered.isCompleted).isFalse()
        assertThat(registry.isAnyWorkActiveOrWaiting()).isTrue()

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertThat(secondEntered.isCompleted).isTrue()
        assertThat(registry.isAnyWorkActiveOrWaiting()).isFalse()
    }

    @Test
    fun `manual and background sync for one provider never overlap`() = runTest {
        val registry = ProviderWorkLockRegistry()
        val releaseManual = CompletableDeferred<Unit>()
        val backgroundEntered = CompletableDeferred<Unit>()
        val manualEntered = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        var maximumActive = 0

        suspend fun run(entered: CompletableDeferred<Unit>, wait: Boolean) {
            registry.withProviderLock(7L) {
                val current = active.incrementAndGet()
                maximumActive = maxOf(maximumActive, current)
                entered.complete(Unit)
                if (wait) releaseManual.await()
                active.decrementAndGet()
            }
        }

        val manual = async { run(manualEntered, wait = true) }
        manualEntered.await()
        val background = async { run(backgroundEntered, wait = false) }
        runCurrent()

        assertThat(backgroundEntered.isCompleted).isFalse()
        releaseManual.complete(Unit)
        manual.await()
        background.await()

        assertThat(maximumActive).isEqualTo(1)
        assertThat(registry.isAnyWorkActiveOrWaiting()).isFalse()
    }

    @Test
    fun `different providers retain independent execution lanes`() = runTest {
        val registry = ProviderWorkLockRegistry()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            registry.withProviderLock(7L) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = async {
            registry.withProviderLock(8L) {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()

        assertThat(secondEntered.isCompleted).isTrue()

        releaseFirst.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `maintenance admission rejects active or queued provider work`() = runTest {
        val registry = ProviderWorkLockRegistry()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val providerWork = async {
            registry.withProviderLock(7L) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        assertThat(registry.runWhenNoWorkActive { true }).isFalse()

        releaseFirst.complete(Unit)
        providerWork.await()

        assertThat(registry.runWhenNoWorkActive { true }).isTrue()
    }

    @Test
    fun `provider work waits until admitted maintenance completes`() = runTest {
        val registry = ProviderWorkLockRegistry()
        val maintenanceEntered = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val providerEntered = CompletableDeferred<Unit>()

        val maintenance = async {
            registry.runWhenNoWorkActive {
                maintenanceEntered.complete(Unit)
                releaseMaintenance.await()
                true
            }
        }
        maintenanceEntered.await()

        val providerWork = async {
            registry.withProviderLock(7L) {
                providerEntered.complete(Unit)
            }
        }
        runCurrent()

        assertThat(providerEntered.isCompleted).isFalse()

        releaseMaintenance.complete(Unit)

        assertThat(maintenance.await()).isTrue()
        providerWork.await()
        assertThat(providerEntered.isCompleted).isTrue()
    }
}
