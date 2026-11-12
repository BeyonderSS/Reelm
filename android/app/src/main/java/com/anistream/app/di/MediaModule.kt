package com.anistream.app.di

import com.anistream.core.media.AddonRepositoryImpl
import com.anistream.core.media.LiveTvRepositoryImpl
import com.anistream.core.media.MoviesRepositoryImpl
import com.anistream.core.media.moviebox.MovieBoxSource
import com.anistream.domain.repository.AddonRepository
import com.anistream.domain.repository.LiveTvRepository
import com.anistream.domain.repository.MoviesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {
    @Provides
    fun provideMoviesRepository(impl: MoviesRepositoryImpl): MoviesRepository = impl

    @Provides
    fun provideAddonRepository(impl: AddonRepositoryImpl): AddonRepository = impl

    @Provides
    fun provideLiveTvRepository(impl: LiveTvRepositoryImpl): LiveTvRepository = impl

    @Provides
    fun provideRecommendationsRepository(impl: com.anistream.core.media.RecommendationsRepositoryImpl): com.anistream.domain.repository.RecommendationsRepository = impl

    @Provides
    fun provideSkipTimesRepository(impl: com.anistream.core.media.AniSkip): com.anistream.domain.repository.SkipTimesRepository = impl

    @Provides
    fun provideMediaSkipTimesRepository(impl: com.anistream.core.media.TheIntroDB): com.anistream.domain.repository.MediaSkipTimesRepository = impl

    @Provides
    fun provideMovieBoxSource(client: com.anistream.core.media.moviebox.MovieBoxClient): MovieBoxSource = client
}
