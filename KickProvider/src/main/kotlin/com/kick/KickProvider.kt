package com.kick

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class KickProvider : MainAPI() {
    override var mainUrl = "https://kick.com"
    override var name = "Kick"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val headers = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/stream/livestreams/en?limit=24&sort=desc&page=" to "Live now (English)",
        "$mainUrl/stream/livestreams/en?limit=24&sort=asc&page=" to "Live now (smaller streamers)"
    )

    // ---------- Data models ----------

    data class LivestreamsResponse(
        @JsonProperty("data") val data: List<LiveItem>? = null
    )

    data class LiveItem(
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("viewer_count") val viewerCount: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: Thumbnail? = null,
        @JsonProperty("channel") val channel: LiveChannel? = null
    )

    data class Thumbnail(@JsonProperty("src") val src: String? = null)

    data class LiveChannel(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("profile_picture") val profilePicture: String? = null
    )

    data class ChannelResponse(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("user") val user: ChannelUser? = null,
        @JsonProperty("playback_url") val playbackUrl: String? = null,
        @JsonProperty("livestream") val livestream: Livestream? = null
    )

    data class ChannelUser(
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("bio") val bio: String? = null,
        @JsonProperty("profile_pic") val profilePic: String? = null
    )

    data class Livestream(
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("viewer_count") val viewerCount: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: Thumbnail? = null,
        @JsonProperty("categories") val categories: List<Category>? = null
    )

    data class Category(@JsonProperty("name") val name: String? = null)

    data class SearchResponse2(
        @JsonProperty("channels") val channels: List<SearchChannel>? = null
    )

    data class SearchChannel(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("profile_pic") val profilePic: String? = null,
        @JsonProperty("is_live") val isLive: Boolean? = null
    )

    // ---------- Main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(request.data + page, headers = headers).parsedSafe<LivestreamsResponse>()
        val items = res?.data.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun LiveItem.toSearchResponse(): LiveSearchResponse? {
        val slug = channel?.slug ?: return null
        val display = channel.username ?: slug
        return newLiveSearchResponse(
            name = if (sessionTitle.isNullOrBlank()) display else "$display - $sessionTitle",
            url = "$mainUrl/$slug",
            type = TvType.Live
        ) {
            this.posterUrl = thumbnail?.src ?: channel.profilePicture
            this.lang = this@KickProvider.lang
        }
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?searched_word=${query.encodeUri()}"
        val res = app.get(url, headers = headers).parsedSafe<SearchResponse2>()
        return res?.channels.orEmpty().mapNotNull {
            val slug = it.slug ?: return@mapNotNull null
            newLiveSearchResponse(
                name = (it.username ?: slug) + if (it.isLive == true) " (LIVE)" else "",
                url = "$mainUrl/$slug",
                type = TvType.Live
            ) {
                this.posterUrl = it.profilePic
            }
        }
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // ---------- Load ----------

    private fun slugFromUrl(url: String): String =
        url.substringAfter("kick.com/").substringBefore("?").trim('/').substringBefore("/")

    override suspend fun load(url: String): LoadResponse {
        val slug = slugFromUrl(url)
        val channel = app.get("$mainUrl/api/v2/channels/$slug", headers = headers)
            .parsedSafe<ChannelResponse>()
            ?: throw ErrorLoadingException("Could not load channel $slug")

        val display = channel.user?.username ?: slug
        val live = channel.livestream
        val title = live?.sessionTitle?.takeIf { it.isNotBlank() }?.let { "$display - $it" } ?: display
        val plot = buildString {
            if (live != null) {
                live.categories?.firstOrNull()?.name?.let { appendLine("Category: $it") }
                live.viewerCount?.let { appendLine("Viewers: $it") }
            } else {
                appendLine("Channel is currently offline.")
            }
            channel.user?.bio?.takeIf { it.isNotBlank() }?.let { appendLine().append(it) }
        }.trim()

        return newLiveStreamLoadResponse(title, "$mainUrl/$slug", "$mainUrl/$slug") {
            this.posterUrl = live?.thumbnail?.src ?: channel.user?.profilePic
            this.plot = plot
        }
    }

    // ---------- Links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = slugFromUrl(data)
        val channel = app.get("$mainUrl/api/v2/channels/$slug", headers = headers)
            .parsedSafe<ChannelResponse>()
        val m3u8 = channel?.playbackUrl?.takeIf { it.isNotBlank() } ?: return false

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = m3u8,
            referer = "$mainUrl/",
            headers = headers
        ).forEach(callback)
        return true
    }
}
