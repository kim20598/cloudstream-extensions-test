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
    override var mainUrl          = "https://ak.sv"
    override var name             = "AkSv"
    override val hasMainPage      = true
    override val supportedTypes   = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.OVA)

    /* ---------- 1.  HOME  ---------- */
    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get("${request.data}$page").document
        val items = doc.select("div.movie-item, div.anime-item").map { card ->
            val title = card.select("h3 a, .title a").text()
            val href  = fixUrl(card.select("h3 a, .title a").attr("href"))
            val poster= fixUrl(card.select("img").attr("data-src"))
            val isMovie = href.contains("/movie/")
            newMovieSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    /* ---------- 2.  SEARCH  ---------- */
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select("div.movie-item, div.anime-item").map { card ->
            val title = card.select("h3 a, .title a").text()
            val href  = fixUrl(card.select("h3 a, .title a").attr("href"))
            val isMovie = href.contains("/movie/")
            newMovieSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
                posterUrl = fixUrl(card.select("img").attr("data-src"))
            }
        }
    }

    /* ---------- 3.  DETAIL  ---------- */
    @SuppressLint("DefaultLocale")
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h1.entry-title, h1.movie-title").text()
        val poster= fixUrl(doc.select("div.poster img, div.cover img").attr("src"))
        val plot  = doc.select("div.entry-content p, div.story p").text()
        val year  = doc.select("span.year, span.release-year").text().toIntOrNull()
        val genres= doc.select("div.genres a, div.tags a").map { it.text() }
        val isMovie = url.contains("/movie/")

        /* ---------- 3a.  EPISODES (TV or Anime)  ---------- */
        val episodes = if (isMovie) emptyList() else {
            val epDoc = app.get("$mainUrl/ajax/episode/list/${url.split("-").last()}")
                .parsedSafe<Response>()?.getDocument() ?: doc
            epDoc.select("ul.episodes > li, div.episode-item").mapIndexed { idx, li ->
                val name = li.select("a, .ep-title").text()
                val href = fixUrl(li.select("a").attr("href"))
                newEpisode(href) {
                    episode = (idx + 1)
                    this.name = name
                }
            }.reversed()
        }

        /* ---------- 3b.  ANIME-ONLY META (AniZip)  ---------- */
        val animeMeta = if (url.contains("/anime/")) {
            val syncData = app.get("https://api.ani.zip/mappings?mal_id=").toString()
            parseAnimeData(syncData)
        } else null

        return (if (isMovie) newMovieLoadResponse(title, url, TvType.Movie) else newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)) {
            posterUrl = poster
            this.plot  = plot
            this.year  = year
            this.tags  = genres
            /* enrich anime episodes only */
            episodes.forEach { ep ->
                val key = ep.episode.toString()
                ep.rating   = animeMeta?.episodes?.get(key)?.rating?.toDoubleOrNull()?.times(10)?.roundToInt() ?: 0
                ep.posterUrl= animeMeta?.episodes?.get(key)?.image
                ep.description = animeMeta?.episodes?.get(key)?.overview ?: "No summary"
            }
        }
    }

    /* ---------- 4.  STREAM LINKS  ---------- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val embed = fixUrl(doc.select("iframe#player").attr("src"))
        /* let shared extractors handle the rest */
        loadExtractor(embed, subtitleCallback, callback)
        return true
    }

    /* ---------- 5.  UTILS  ---------- */
    private inline fun <reified T> String.parsedSafe(): T? = try { Gson().fromJson(this, T::class.java) } catch (e: Exception) { null }
    private fun parseAnimeData(json: String): MetaAnimeData? = try { ObjectMapper().readValue(json, MetaAnimeData::class.java) } catch (e: Exception) { null }

    data class Response(val html: String) { fun getDocument() = Jsoup.parse(html) }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(val coverType: String?, val url: String?)
    data class MetaEpisode(val image: String?, val title: Map<String, String>?, val overview: String?, val rating: String?)
    data class MetaAnimeData(val images: List<MetaImage>?, val episodes: Map<String, MetaEpisode>?)

    /* ---------- 6.  HOME ROWS  ---------- */
    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "أفلام",
        "$mainUrl/series?page=" to "مسلسلات",
        "$mainUrl/anime?page=" to "أنمي",
        "$mainUrl/anime?dub=1&page=" to "أنمي مدبلج",
        "$mainUrl/movies?lang=ar&page=" to "أفلام عربية",
        "$mainUrl/top?page=" to "الأكثر مشاهدة"
    )
}
