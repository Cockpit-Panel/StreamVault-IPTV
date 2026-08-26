package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DiscoveryBudget
import org.junit.Test

class StalkerDiscoveryBudgetTest {

    @Test
    fun `request budget stops deterministically at configured limit`() {
        val runtime = StalkerDiscoveryRuntime(
            DiscoveryBudget(maxElapsedMillis = 60_000L, maxRequests = 2)
        )
        runtime.begin()

        runtime.consumeRequest()
        runtime.consumeRequest()
        val failure = runCatching(runtime::consumeRequest).exceptionOrNull()

        assertThat(runtime.requestCount).isEqualTo(3)
        assertThat(failure).isInstanceOf(StalkerApiError.DiscoveryBudgetExceeded::class.java)
        runtime.end()
    }

    @Test
    fun `live category sampling prioritizes reported non-empty groups and keeps spread`() {
        val categories = (0 until 20).map { index ->
            StalkerCategoryRecord(
                id = index.toString(),
                name = "Category $index",
                advertisedItemCount = when (index) {
                    3 -> 500
                    17 -> 200
                    else -> null
                }
            )
        }

        val sampled = sampleLiveDiscoveryCategories(categories, limit = 8)

        assertThat(sampled).hasSize(8)
        assertThat(sampled.take(2).map(StalkerCategoryRecord::id))
            .containsExactly("3", "17")
            .inOrder()
        assertThat(sampled.map(StalkerCategoryRecord::id)).contains("0")
        assertThat(sampled.map(StalkerCategoryRecord::id)).contains("19")
    }
}
