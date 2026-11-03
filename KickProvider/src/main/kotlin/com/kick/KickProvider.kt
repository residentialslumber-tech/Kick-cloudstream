package com.kick

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*

class KickProvider : MainAPI() {
    override var mainUrl = "https://kick.com"
    override var name = "Kick"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
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

    data class VodItem(
        @JsonProperty("uuid") val uuid: String? = null,
        @JsonProperty("video") val video: VodVideo? = null,
        @JsonProperty("thumb") val thumb: Any? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("livestream") val livestream: VodStream? = null
    )

    data class VodVideo(
        @JsonProperty("uuid") val uuid: String? = null,
        @JsonProperty("thumb") val thumb: Any? = null,
        @JsonProperty("source") val source: String? = null
    )

    data class VodStream(
        @JsonProperty("session_title") val sessionTitle: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("thumbnail") val thumbnail: Any? = null
    )

    data class VodSource(@JsonProperty("source") val source: String? = null)

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

        val vods = fetchVods(slug)
        val poster = live?.thumbnail?.src ?: channel.user?.profilePic

        if (vods.isEmpty()) {
            return newLiveStreamLoadResponse(title, "$mainUrl/$slug", "live:$slug") {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val episodes = ArrayList<Episode>()
        if (live != null) {
            episodes.add(newEpisode("live:$slug") {
                this.name = "LIVE - ${live.sessionTitle ?: display}"
                this.posterUrl = live.thumbnail?.src
                this.episode = 0
            })
        }
        vods.forEachIndexed { index, vod ->
            val id = vod.video?.uuid ?: vod.uuid ?: return@forEachIndexed
            val stream = vod.livestream
            val date = (stream?.createdAt ?: vod.createdAt)?.take(10)
            val label = stream?.sessionTitle?.takeIf { it.isNotBlank() } ?: "Broadcast"
            val src = vod.source ?: vod.video?.source
            episodes.add(newEpisode("vod:$id::${src.orEmpty()}") {
                this.name = if (date != null) "$date - $label" else label
                this.posterUrl = thumbOf(stream?.thumbnail) ?: thumbOf(vod.video?.thumb)
                    ?: thumbOf(vod.thumb) ?: poster
                this.episode = vods.size - index
                this.runTime = stream?.duration?.let { (it / 60000).toInt() }
            })
        }

        return newTvSeriesLoadResponse(title, "$mainUrl/$slug", TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun thumbOf(t: Any?): String? = when (t) {
        is String -> t
        is Map<*, *> -> (t["src"] as? String) ?: (t["url"] as? String)
            ?: (t["srcset"] as? String)?.substringBefore(" ")?.takeIf { it.startsWith("http") }
        else -> null
    }

    private suspend fun fetchVods(slug: String): List<VodItem> = try {
        app.get("$mainUrl/api/v2/channels/$slug/videos", headers = headers)
            .parsedSafe<Array<VodItem>>()?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private val cfKiller = CloudflareKiller()

    private suspend fun getText(url: String): String? {
        val plain = try { app.get(url, headers = headers) } catch (e: Exception) { null }
        if (plain != null && plain.isSuccessful) return plain.text
        val viaCf = try { app.get(url, headers = headers, interceptor = cfKiller) } catch (e: Exception) { null }
        return viaCf?.takeIf { it.isSuccessful }?.text
    }

    private fun jsonField(text: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(text)
            ?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { it.startsWith("http") }

    // ---------- Links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("vod:")) {
            val body = data.removePrefix("vod:")
            val uuid = body.substringBefore("::")
            var src = body.substringAfter("::", "").takeIf { it.startsWith("http") }

            if (src == null) {
                for (u in listOf("$mainUrl/api/v1/video/$uuid", "$mainUrl/api/v2/video/$uuid")) {
                    val text = getText(u) ?: continue
                    src = jsonField(text, "source")
                    if (src != null) break
                }
            }
            val url = src ?: return false

            val links = try {
                M3u8Helper.generateM3u8(name, url, "$mainUrl/", headers = headers)
            } catch (e: Exception) {
                emptyList()
            }
            if (links.isNotEmpty()) {
                links.forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, "$name VOD", url, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = this@KickProvider.headers
                    }
                )
            }
            return true
        }

        val slug = if (data.startsWith("live:")) data.removePrefix("live:") else slugFromUrl(data)
        val m3u8 = jsonField(getText("$mainUrl/api/v2/channels/$slug/livestream").orEmpty(), "playback_url")
            ?: jsonField(getText("$mainUrl/api/v2/channels/$slug").orEmpty(), "playback_url")
            ?: return false

        val links = try {
            M3u8Helper.generateM3u8(name, m3u8, "$mainUrl/", headers = headers)
        } catch (e: Exception) {
            emptyList()
        }
        if (links.isNotEmpty()) {
            links.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, "$name Live", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = this@KickProvider.headers
                }
            )
        }
        return true
    }
}
