package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderWorkflowDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.ProviderWorkflowEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.entity.ProviderWorkflowState
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProviderSyncStateReaderImplTest {
    private val syncManager: SyncManager = mock()
    private val xtreamIndexJobDao: XtreamIndexJobDao = mock()
    private val workflowDao: ProviderWorkflowDao = mock()

    @Test
    fun `durable queued index phase is visible without legacy job state`() = runTest {
        whenever(xtreamIndexJobDao.observeForProvider(PROVIDER_ID)).thenReturn(flowOf(emptyList()))
        whenever(workflowDao.observeWorkflow(PROVIDER_ID)).thenReturn(
            flowOf(
                ProviderWorkflowEntity(
                    providerId = PROVIDER_ID,
                    generation = 3L,
                    state = ProviderWorkflowState.PENDING,
                    reason = ProviderWorkflowReason.PERIODIC,
                    createdAt = 100L,
                    updatedAt = 100L
                )
            )
        )
        whenever(workflowDao.observeActivePhases(PROVIDER_ID)).thenReturn(
            flowOf(listOf(ProviderWorkflowPhase.MOVIE_INDEX))
        )
        val reader = ProviderSyncStateReaderImpl(syncManager, xtreamIndexJobDao, workflowDao)

        assertThat(reader.observeBackgroundIndexingActive(PROVIDER_ID).first()).isTrue()
    }

    @Test
    fun `terminal durable workflow does not report background indexing`() = runTest {
        whenever(xtreamIndexJobDao.observeForProvider(PROVIDER_ID)).thenReturn(
            flowOf(
                listOf(
                    XtreamIndexJobEntity(
                        providerId = PROVIDER_ID,
                        section = "MOVIE",
                        state = "RUNNING"
                    )
                )
            )
        )
        whenever(workflowDao.observeWorkflow(PROVIDER_ID)).thenReturn(
            flowOf(
                ProviderWorkflowEntity(
                    providerId = PROVIDER_ID,
                    generation = 3L,
                    state = ProviderWorkflowState.SUCCEEDED,
                    reason = ProviderWorkflowReason.PERIODIC,
                    createdAt = 100L,
                    updatedAt = 200L,
                    completedAt = 200L
                )
            )
        )
        whenever(workflowDao.observeActivePhases(PROVIDER_ID)).thenReturn(flowOf(emptyList()))
        val reader = ProviderSyncStateReaderImpl(syncManager, xtreamIndexJobDao, workflowDao)

        assertThat(reader.observeBackgroundIndexingActive(PROVIDER_ID).first()).isFalse()
    }

    @Test
    fun `legacy indexing remains a fallback before durable workflow exists`() = runTest {
        whenever(xtreamIndexJobDao.observeForProvider(PROVIDER_ID)).thenReturn(
            flowOf(
                listOf(
                    XtreamIndexJobEntity(
                        providerId = PROVIDER_ID,
                        section = "SERIES",
                        state = "RUNNING"
                    )
                )
            )
        )
        whenever(workflowDao.observeWorkflow(PROVIDER_ID)).thenReturn(flowOf(null))
        whenever(workflowDao.observeActivePhases(PROVIDER_ID)).thenReturn(flowOf(emptyList()))
        val reader = ProviderSyncStateReaderImpl(syncManager, xtreamIndexJobDao, workflowDao)

        assertThat(reader.observeBackgroundIndexingActive(PROVIDER_ID).first()).isTrue()
    }

    private companion object {
        const val PROVIDER_ID = 7L
    }
}
