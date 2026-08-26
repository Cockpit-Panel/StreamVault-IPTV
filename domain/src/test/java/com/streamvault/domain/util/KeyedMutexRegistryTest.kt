package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred

class KeyedMutexRegistryTest {
    @Test
    fun completedUniqueKeysAreNotRetained() = runTest {
        val registry = KeyedMutexRegistry<Int>()

        (0 until 100_000).chunked(1_000).forEach { keys ->
            keys.map { key -> async { registry.withLock(key) { key } } }.awaitAll()
        }

        assertThat(registry.sizeForTests()).isEqualTo(0)
    }

    @Test
    fun forgetDuringUseDoesNotBreakSerializationAndReAddIsClean() = runTest {
        val registry = KeyedMutexRegistry<Long>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            registry.withLock(7L) {
                order += "first-enter"
                entered.complete(Unit)
                release.await()
                order += "first-exit"
            }
        }
        entered.await()
        registry.forget(7L)
        val second = async {
            registry.withLock(7L) { order += "second" }
        }
        release.complete(Unit)
        first.await()
        second.await()

        registry.forget(7L)
        registry.withLock(7L) { order += "re-added" }

        assertThat(order).containsExactly("first-enter", "first-exit", "second", "re-added").inOrder()
        assertThat(registry.sizeForTests()).isEqualTo(0)
    }
}
