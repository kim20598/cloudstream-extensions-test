package com.aksv

import android.annotation.SuppressLint
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.net.URI

class AkSvProvider : MainAPI() {
    override var mainUrl          = BuildConfig.AKSV_API
    override var name             = "AkSv"
    override val hasMainPage      = true
    override val supportedTypes   = setOf(TvType.Anime, TvType.OVA)

    /* 1.  HOME  */
    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get("${request.data}$page").document
        val items = doc.select("div.flw-item").map { card ->
            val title = card.select("h3.film-name a").text()
            val href  = fixUrl(card.select("h3.film-name a").attr("href"))
            val poster= fixUrl(card.select("img").attr("data-src"))
            val isMovie = href.contains("/movie/")
            newAnimeSearchResponse(title, href, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                this.posterUrl = poster
                val sub = card.select(".tick-sub").text().toIntOrNull()
                val dub = card.select(".tick-dub").text().toIntOrNull()
                addDubStatus(dub != null, sub != null, dub, sub)
            }
        }
        return newHomePageResponse(request.name, items)
    }

    /* 2.  SEARCH  */
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?keyword=$query").document
        return doc.select("div.flw-item").map { card ->
            val title = card.select("h3.film-name a").text()
            val href  = fixUrl(card.select("h3.film-name a").attr("href"))
            val isMovie = href.contains("/movie/")
            newAnimeSearchResponse(title, href, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                posterUrl = fixUrl(card.select("img").attr("data-src"))
            }
        }
    }

    /* 3.  DETAIL + EPISODES  */
    @SuppressLint("DefaultLocale")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val syncData = tryParseJson<ZoroSyncData>(document.selectFirst("#syncData")?.data())
        val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=${syncData?.malId}").toString()
        val animeMetaData = parseAnimeData(syncMetaData)
        val title = document.selectFirst(".anisc-detail > .film-name")?.text().toString()
        val poster = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url
            ?: document.selectFirst(".anisc-poster img")?.attr("src")
        val animeId = URI(url).path.split("-").last()
        val subCount = document.selectFirst(".anisc-detail .tick-sub")?.text()?.toIntOrNull()
        val dubCount = document.selectFirst(".anisc-detail .tick-dub")?.text()?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val responseBody = app.get("$mainUrl/ajax/v2/episode/list/$animeId").body.string()
        val epRes = responseBody.stringParse<Response>()?.getDocument()

        epRes?.select(".ss-list > a[href].ssl-item.ep-item")?.forEachIndexed { index, ep ->
            subCount?.let {
                if (index < it) {
                    val href = ep.attr("href").removePrefix("/")
                    val episodeData = "sub|$href"
                    episodes += newEpisode(episodeData) {
                        name = ep.attr("title")
                        episode = ep.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                        val episodeKey = episode?.toString()
                        this.rating = animeMetaData?.episodes?.get(episodeKey)?.rating
                            ?.toDoubleOrNull()
                            ?.times(10)
                            ?.roundToInt() ?: 0
                        this.posterUrl = animeMetaData?.episodes?.get(episodeKey)?.image
                        this.description = animeMetaData?.episodes?.get(episodeKey)?.overview
                            ?: "No summary available"
                    }
                }
            }
            dubCount?.let {
                if (index < it) {
                    episodes += newEpisode("dub|" + ep.attr("href")) {
                        name = ep.attr("title")
                        episode = ep.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                        val episodeKey = episode?.toString()
                        this.rating = animeMetaData?.episodes?.get(episodeKey)?.rating
                            ?.toDoubleOrNull()
                            ?.times(10)
                            ?.roundToInt() ?: 0
                        this.posterUrl = animeMetaData?.episodes?.get(episodeKey)?.image
                        this.description = animeMetaData?.episodes?.get(episodeKey)?.overview
                            ?: "No summary available"
                    }
                }
            }
        }

        val actors = document.select("div.block-actors-content div.bac-item").mapNotNull { it.getActorData() }
        val recommendations = document.select("div.block_area_category div.flw-item").map { it.toSearchResult() }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            addEpisodes(DubStatus.Dubbed, episodes)
            this.recommendations = recommendations
            this.actors = actors

            document.select(".anisc-info > .item").forEach { info ->
                val infoType = info.select("span.item-head").text().removeSuffix(":")
                when (infoType) {
                    "Overview" -> plot = info.selectFirst(".text")?.text()
                    "Japanese" -> japName = info.selectFirst(".name")?.text()
                    "Premiered" -> year = info.selectFirst(".name")?.text()?.substringAfter(" ")?.toIntOrNull()
                    "Duration" -> duration = getDurationFromString(info.selectFirst(".name")?.text())
                    "Status" -> showStatus = getStatus(info.selectFirst(".name")?.text().toString())
                    "Genres" -> tags = info.select("a").map { it.text() }
                    "MAL Score" -> rating = info.selectFirst(".name")?.text().toRatingInt()
                    else -> {}
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val dubType = data.removePrefix("$mainUrl/").substringBefore("|").ifEmpty { "raw" }
            val hrefPart = data.substringAfterLast("|")
            val epId = hrefPart.substringAfter("ep=")

            val doc = app.get("$mainUrl/ajax/v2/episode/servers?episodeId=$epId")
                .parsed<Response>()?.getDocument() ?: return false

            val servers = doc.select(".server-item[data-id]").mapNotNull {
                val id = it.attr("data-id")
                val label = it.selectFirst("a.btn")?.text()?.trim()
                if (id.isNotEmpty() && label != null) id to label else null
            }.distinctBy { it.first }

            servers.forEach { (id, label) ->
                val sourceUrl = app.get("${mainUrl}/ajax/v2/episode/sources?id=$id").parsedSafe<SourcesResp>()?.link
                if (sourceUrl != null) {
                    loadCustomExtractor("AkSv [$label]", sourceUrl, "", subtitleCallback, callback)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("AkSv", "Critical error in loadLinks: ${e.localizedMessage}")
            return false
        }
    }

    private inline fun <reified T> String.parsedSafe(): T? = try { Gson().fromJson(this, T::class.java) } catch (e: Exception) { null }
    private fun parseAnimeData(json: String): MetaAnimeData? = try { ObjectMapper().readValue(json, MetaAnimeData::class.java) } catch (e: Exception) { null }

    data class Response(val html: String) { fun getDocument() = Jsoup.parse(html) }
    data class SourcesResp(val link: String)

    data class ZoroSyncData(@JsonProperty("mal_id") val malId: String?, @JsonProperty("anilist_id") val aniListId: String?)

    data class ActorData(val actor: Actor, val role: ActorRole?, val voiceActor: Actor?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(@JsonProperty("coverType") val coverType: String?, @JsonProperty("url") val url: String?)
    data class MetaEpisode(val image: String?, val title: Map<String, String>?, val overview: String?, val rating: String?)
    data class MetaAnimeData(val images: List<MetaImage>?, val episodes: Map<String, MetaEpisode>?)

    private suspend fun loadCustomExtractor(name: String?, url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, quality: Int? = null) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(newExtractorLink(name ?: link.source, name ?: link.name, link.url) {
                    this.quality = when {
                        link.name == "VidSrc" -> Qualities.P1080.value
                        link.type == ExtractorLinkType.M3U8 -> link.quality
                        else -> quality ?: link.quality
                    }
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                })
            }
        }
    }
}
