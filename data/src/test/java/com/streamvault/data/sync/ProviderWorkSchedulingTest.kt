package com.streamvault.data.sync

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderWorkSchedulingTest {
    @Test
    fun `all provider phases resolve to one unique work identity`() {
        val identities = listOf(
            providerWorkUniqueName(7L),
            providerWorkUniqueName(7L),
            providerWorkUniqueName(7L),
            providerWorkUniqueName(7L)
        )

        assertThat(identities.distinct()).containsExactly("provider-workflow-7")
    }

    @Test
    fun `ordinary provider phases append without inheriting failed prerequisites`() {
        assertThat(providerWorkExistingPolicy(supersede = false))
            .isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    @Test
    fun `configuration revision explicitly supersedes older provider work`() {
        assertThat(providerWorkExistingPolicy(supersede = true))
            .isEqualTo(ExistingWorkPolicy.REPLACE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `provider work identity rejects invalid provider id`() {
        providerWorkUniqueName(0L)
    }
}
