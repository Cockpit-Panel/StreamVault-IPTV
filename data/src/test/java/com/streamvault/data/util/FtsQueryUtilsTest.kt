package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FtsQueryUtilsTest {

    @Test
    fun `toFtsPrefixQuery joins multiple tokens with whitespace for fts4`() {
        assertThat("no place".toFtsPrefixQuery()).isEqualTo("no* place*")
    }

    @Test
    fun `toFtsPrefixQuery strips punctuation and ignores one character tokens`() {
        assertThat("n!o p-lace a".toFtsPrefixQuery()).isEqualTo("no* place*")
    }
}