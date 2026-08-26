package com.streamvault.app.di

import com.streamvault.app.cast.CastPlaybackCoordinator
import com.streamvault.app.cast.DefaultCastPlaybackCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CastModule {
    @Binds
    @Singleton
    abstract fun bindCastPlaybackCoordinator(
        impl: DefaultCastPlaybackCoordinator
    ): CastPlaybackCoordinator
}
