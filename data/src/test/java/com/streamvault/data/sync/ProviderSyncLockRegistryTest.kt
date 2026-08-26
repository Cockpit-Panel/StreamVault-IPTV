package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSyncLockRegistryTest {
    @Test
    fun `same category hydration is serialized`() = runTest {
        val registry = ProviderSyncLockRegistry()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            registry.withVodCategoryLock(7L, 11L, splitCatalog = false) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = async {
            registry.withVodCategoryLock(7L, 11L, splitCatalog = false) {
                secondEntered.complete(Unit)
            }
        }
        runCurrent()

        assertThat(secondEntered.isCompleted).isFalse()
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertThat(secondEntered.isCompleted).isTrue()
    }

    @Test
    fun `different category and section keys do not block each other`() = runTest {
        val registry = ProviderSyncLockRegistry()
        val categoryEntered = CompletableDeferred<Unit>()
        val sectionEntered = CompletableDeferred<Unit>()

        val category = async {
            registry.withVodCategoryLock(7L, 11L, splitCatalog = false) {
                categoryEntered.complete(Unit)
            }
        }
        val section = async {
            registry.withStalkerIndexSectionLock(7L, ContentType.MOVIE) {
                sectionEntered.complete(Unit)
            }
        }

        category.await()
        section.await()
        assertThat(categoryEntered.isCompleted).isTrue()
        assertThat(sectionEntered.isCompleted).isTrue()
    }
}
