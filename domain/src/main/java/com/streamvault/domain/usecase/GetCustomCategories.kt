package com.streamvault.domain.usecase

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VirtualGroup
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

class GetCustomCategories @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val channelRepository: ChannelRepository
) {
    private val logger = Logger.getLogger("GetCustomCategories")
    operator fun invoke(providerId: Long, contentType: ContentType = ContentType.LIVE): Flow<List<Category>> =
        invoke(listOf(providerId), contentType)

    operator fun invoke(providerIds: List<Long>, contentType: ContentType = ContentType.LIVE): Flow<List<Category>> {
        if (providerIds.isEmpty()) {
            return flowOf(emptyList())
        }

        val favoritesFlow = favoriteRepository.getAllFavorites(providerIds, contentType)
        val visibleLiveChannelsFlow = if (contentType == ContentType.LIVE) {
            favoritesFlow
                .map { favorites -> favorites.map(Favorite::contentId).distinct() }
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        channelRepository.getChannelsByIds(ids)
                    }
                }
        } else {
            flowOf(emptyList())
        }

        return combine(
            favoriteRepository.getGroups(providerIds, contentType),
            favoritesFlow,
            visibleLiveChannelsFlow
        ) { groups, favorites, visibleLiveChannels ->
            buildCategories(
                groups = groups,
                favorites = favorites,
                contentType = contentType,
                visibleLiveChannels = visibleLiveChannels
            )
        }.catch { e ->
            logger.log(Level.WARNING, "Failed to load custom categories", e)
            emit(emptyList())
        }
    }

    private fun buildCategories(
        groups: List<VirtualGroup>,
        favorites: List<Favorite>,
        contentType: ContentType,
        visibleLiveChannels: List<Channel>
    ): List<Category> {
        val groupCounts = if (contentType == ContentType.LIVE) {
            buildVisibleLiveGroupCounts(favorites, visibleLiveChannels)
        } else {
            favorites
                .asSequence()
                .mapNotNull(Favorite::groupId)
                .groupingBy { it }
                .eachCount()
        }
        val globalCount = if (contentType == ContentType.LIVE) {
            countVisibleLiveFavorites(
                contentIds = favorites.asSequence()
                    .filter { it.groupId == null }
                    .map(Favorite::contentId)
                    .toSet(),
                visibleLiveChannels = visibleLiveChannels
            )
        } else {
            favorites.count { it.groupId == null }
        }

        val categories = groups.map { group ->
            Category(
                id = -group.id,
                name = group.name,
                type = contentType,
                isVirtual = true,
                count = groupCounts.getOrDefault(group.id, 0)
            )
        }.toMutableList()

        categories.add(
            index = 0,
            element = Category(
                id = VirtualCategoryIds.FAVORITES,
                name = "Favorites",
                type = contentType,
                isVirtual = true,
                count = globalCount
            )
        )

        return categories.toList()
    }

    private fun buildVisibleLiveGroupCounts(
        favorites: List<Favorite>,
        visibleLiveChannels: List<Channel>
    ): Map<Long, Int> {
        val contentIdsByGroup = favorites
            .asSequence()
            .mapNotNull { favorite -> favorite.groupId?.let { groupId -> groupId to favorite.contentId } }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second }
            )

        return contentIdsByGroup.mapValues { (_, contentIds) ->
            countVisibleLiveFavorites(contentIds.toSet(), visibleLiveChannels)
        }
    }

    private fun countVisibleLiveFavorites(
        contentIds: Set<Long>,
        visibleLiveChannels: List<Channel>
    ): Int {
        if (contentIds.isEmpty()) return 0
        return visibleLiveChannels.count { channel ->
            channel.allVariantRawIds().any(contentIds::contains)
        }
    }
}
