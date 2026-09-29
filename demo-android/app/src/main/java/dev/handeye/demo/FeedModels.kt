package dev.handeye.demo

import kotlinx.serialization.Serializable

@Serializable
data class FeedItem(val id: Int, val title: String, val liked: Boolean = false)

@Serializable
data class FeedPage(val items: List<FeedItem>)
