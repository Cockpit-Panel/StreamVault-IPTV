package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SyncManagerXtreamSupportTest {

    private val adaptiveSyncPolicy = XtreamAdaptiveSyncPolicy()

    private val support = SyncManagerXtreamSupport(
        adaptiveSyncPolicy = adaptiveSyncPolicy,
        shouldRememberSequentialPreference = { false },
        sanitizeThrowableMessage = { it?.message.orEmpty() },
        progress = { _, _, _ -> },
        movieRequestTimeoutMillis = 60_000L,
        seriesRequestTimeoutMillis = 60_000L,
        recoveryAbortWarningSuffix = "aborted"
    )

    @Test
    fun `executeXtreamRequest converts timeout cancellations into io failures`() = runTest {
        val providerId = 7L

        val failure = runCatching {
            support.executeXtreamRequest(providerId, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                withTimeout(1) {
                    delay(10)
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure).hasMessageThat().contains("35 seconds")
        assertThat(
            adaptiveSyncPolicy.concurrencyFor(
                providerId = providerId,
                workloadSize = 10,
                preferSequential = false,
                stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
            )
        ).isEqualTo(1)
    }

    @Test
    fun `continueFailedCategoryOutcomes reports sequential retry progress`() = runTest {
        val provider = Provider(
            id = 7L,
            name = "Test Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test"
        )
        val failedCategory = XtreamCategory(categoryId = "13", categoryName = "Sports")
        val progress = mutableListOf<String>()

        val retried = support.continueFailedCategoryOutcomes(
            provider = provider,
            timedOutcomes = listOf(
                TimedCategoryOutcome(
                    category = XtreamCategory(categoryId = "12", categoryName = "News"),
                    outcome = CategoryFetchOutcome.Success("News", emptyList()),
                    elapsedMs = 25L
                ),
                TimedCategoryOutcome(
                    category = failedCategory,
                    outcome = CategoryFetchOutcome.Failure("Sports", IOException("boom")),
                    elapsedMs = 50L
                )
            ),
            fetchSequentially = { category ->
                TimedCategoryOutcome(
                    category = category,
                    outcome = CategoryFetchOutcome.Success(category.categoryName, emptyList()),
                    elapsedMs = 30L
                )
            },
            onCategoryRetried = { completed, total, currentLabel ->
                progress += "$completed/$total:$currentLabel"
            }
        )

        assertThat(progress).containsExactly("1/1:Sports")
        assertThat(retried.last().outcome).isInstanceOf(CategoryFetchOutcome.Success::class.java)
    }
}
