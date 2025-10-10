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
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
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
        val doc = app.get("$mainUrl/latest?page=$page").document
        val items = doc.select("div.anime-card").map { card ->
            newAnimeSearchResponse(
                card.select("h3.title").text(),
                fixUrl(card.select("a").attr("href"))
            ){
                posterUrl = fixUrl(card.select("img").attr("src"))
                val sub = card.select("span.sub-count").text().toIntOrNull()
                val dub = card.select("span.dub-count").text().toIntOrNull()
                addDubStatus(dub != null, sub != null, dub, sub)
            }
        }
        return newHomePageResponse("Latest", items, hasNext = true)
    }

    /* 2.  SEARCH  */
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc   = app.get("$mainUrl/search?q=$encoded").document
        return doc.select("div.anime-card").map { card ->
            newAnimeSearchResponse(
                card.select("h3").text(),
                fixUrl(card.select("a").attr("href"))
            ){ posterUrl = fixUrl(card.select("img").attr("src")) }
        }
    }

    /* 3.  DETAIL + EPISODES  */
    @SuppressLint("DefaultLocale")
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val animeId = URI(url).path.split("-").last()
        val title = doc.select("h1.anime-title").text()
        val poster= fixUrl(doc.select("img.poster").attr("src"))
        val plot  = doc.select("p.synopsis").text()
        val year  = doc.select("span.year").text().toIntOrNull()
        val genres= doc.select("div.genres > a").map { it.text() }

        /* per-episode enrichment (ani.zip) */
        val syncData = app.get("https://api.ani.zip/mappings?mal_id=").toString()
        val animeMeta = parseAnimeData(syncData)

        /* episode list */
        val epDoc = app.get("$mainUrl/ajax/episode/list/$animeId")
            .parsedSafe<Response>()?.getDocument() ?: doc
        val episodes = epDoc.select("ul.episodes > li").mapIndexed { idx, li ->
            val name = li.select("a").text()
            val href = fixUrl(li.select("a").attr("href"))
            val ep   = li.select("span.ep-num").text().toIntOrNull() ?: (idx + 1)
            val key  = ep.toString()
            newEpisode(href){
                this.name        = name
                this.episode     = ep
                this.rating      = animeMeta?.episodes?.get(key)?.rating?.toDoubleOrNull()?.times(10)?.roundToInt() ?: 0
                this.posterUrl   = animeMeta?.episodes?.get(key)?.image
                this.description = animeMeta?.episodes?.get(key)?.overview ?: "No summary"
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot  = plot
            this.year  = year
            this.tags  = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /* 4.  STREAM LINKS  ---------- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = data.substringAfterLast("/").substringBefore("?")
        val serverDoc = app.get("$mainUrl/ajax/episode/servers?episodeId=$epId")
            .parsedSafe<Response>()?.getDocument() ?: return false

        /* pick first server that replies */
        serverDoc.select("div.server-item[data-id]").firstOrNull()?.let { srv ->
            val serverId = srv.attr("data-id")
            val linkJson = app.get("$mainUrl/ajax/episode/sources?id=$serverId").parsedSafe<SourcesResp>()
            val embedUrl = linkJson?.link ?: return false
            loadExtractor(embedUrl, subtitleCallback, callback)
        }
        return true
    }

    /* ---------- UTILS  ---------- */
    private inline fun <reified T> String.parsedSafe(): T? = try {
        Gson().fromJson(this, T::class.java)
    } catch (e: Exception) { null }

    private fun parseAnimeData(json: String): MetaAnimeData? = try {
        ObjectMapper().readValue(json, MetaAnimeData::class.java)
    } catch (e: Exception) { null }

    /* ---------- DATA CLASSES  ---------- */
    data class Response(val html: String) { fun getDocument() = Jsoup.parse(html) }
    data class SourcesResp(val link: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(@JsonProperty("coverType") val coverType: String?, @JsonProperty("url") val url: String?)
    data class MetaEpisode(
        @JsonProperty("image") val image: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("rating") val rating: String?
    )
    data class MetaAnimeData(
        @JsonProperty("images") val images: List<MetaImage>?,
        @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?
    )
}
