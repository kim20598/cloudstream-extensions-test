package com.AkSv

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import com.AkSv.AkSvExtractors.invokeInternalSources        // same file, renamed package
import com.AkSv.AkSvParser.AkSvLoadData
import com.AkSv.AkSvParser.AkSvQuery
import com.AkSv.AkSvParser.Detail
import com.AkSv.AkSvParser.Edges
import com.AkSv.AkSvParser.JikanResponse
import com.AkSv.AkSvUtils.aniToMal
import com.AkSv.AkSvUtils.getTracker
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import com.phisher98.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import kotlin.math.roundToInt

@Suppress("UNCHECKED_CAST")
open class AkSvProvider : MainAPI() {
    override var name = "AkSv"
    override val instantLinkLoading = true
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val supportedSyncNames = setOf(SyncIdName.Anilist, SyncIdName.MyAnimeList)
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    /* ---------------------------------------------------------- */
    /* 1.  STATUS HELPER                                          */
    /* ---------------------------------------------------------- */
    private fun getStatus(t: String): ShowStatus = when (t) {
        "Finished"  -> ShowStatus.Completed
        "Releasing" -> ShowStatus.Ongoing
        else        -> ShowStatus.Completed
    }

    /* ---------------------------------------------------------- */
    /* 2.  BUILD-PAGE ROWS  (mimic Anichi layout)                 */
    /* ---------------------------------------------------------- */
    @RequiresApi(Build.VERSION_CODES.O)
    val currentYear = LocalDate.now().year

    @SuppressLint("NewApi")
    override val mainPage = mainPageOf(
        """$baseUrl/latest?page=%d""" to "Latest Episodes",
        """$baseUrl/popular?page=%d"""  to "Popular"
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(request.data.format(page)).document
        val cards = doc.select("div.anime-card").mapNotNull { card ->
            val title = card.select("h3.title").text()
            val href  = fixUrl(card.select("a").attr("href"))
            val thumb = fixUrl(card.select("img").attr("src"))
            val epSub = card.select("span.ep-sub").text().toIntOrNull() ?: 0
            val epDub = card.select("span.ep-dub").text().toIntOrNull() ?: 0
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(title, href, fix = false) {
                posterUrl = thumb
                addSub(epSub)
                addDub(epDub)
            }
        }
        return newHomePageResponse(request.name, cards, hasNext = true)
    }

    /* ---------------------------------------------------------- */
    /* 3.  SEARCH                                                 */
    /* ---------------------------------------------------------- */
    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        }
        val doc = app.get("$baseUrl/search?q=$encoded").document
        return doc.select("div.anime-card").mapNotNull { card ->
            val title = card.select("h3.title").text()
            val href  = fixUrl(card.select("a").attr("href"))
            val thumb = fixUrl(card.select("img").attr("src"))
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(title, href, fix = false) {
                posterUrl = thumb
            }
        }
    }

    /* ---------------------------------------------------------- */
    /* 4.  LOAD DETAIL + EPISODES                                 */
    /* ---------------------------------------------------------- */
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val id = url.substringAfterLast("/")

        val title       = doc.select("h1.anime-title").text()
        val description = doc.select("p.synopsis").text()
        val poster      = fixUrl(doc.select("img.poster").attr("src"))
        val statusText  = doc.select("span.status").text()
        val genres      = doc.select("div.genres > a").map { it.text() }
        val year        = doc.select("span.year").text().toIntOrNull()
        val trailer     = doc.select("div.trailer[data-id]").attr("data-id")
                          .takeIf { it.isNotBlank() }
                          ?.let { "https://www.youtube.com/watch?v=$it" }

        /* episodes list */
        val episodes = doc.select("ul.episodes > li").map { li ->
            val name = li.select("a").text()
            val href = fixUrl(li.select("a").attr("href"))
            val ep   = li.select("span.ep-num").text().toIntOrNull() ?: 1
            newEpisode(AkSvLoadData(id, href).toJson()) {
                this.name = name
                this.episode = ep
            }
        }.reversed()

        /* trackers (reuse Anichi util, just re-package) */
        val trackers = getTracker(title, null, year, null, "TV")
        val syncData = app.get("https://api.ani.zip/mappings?mal_id=${trackers?.idMal}").toString()
        val animeData = parseAnimeData(syncData)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName        = doc.select("span.english-title").text()
            posterUrl      = animeData?.images?.firstOrNull { it.coverType == "Fanart" }?.url ?: poster
            this.year      = year
            this.tags      = genres
            showStatus     = getStatus(statusText)
            plot           = description
            addTrailer(trailer)
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(trackers?.idMal)
            addAniListId(trackers?.id)
        }
    }

    /* ---------------------------------------------------------- */
    /* 5.  EXTRACT STREAMS                                        */
    /* ---------------------------------------------------------- */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<AkSvLoadData>(data)
        val episodeDoc = app.get(loadData.href).document

        /* grab embed */
        val embed = fixUrl(episodeDoc.select("iframe#player").attr("src"))
        val script = app.get(embed, referer = loadData.href)
            .document.select("script:containsData(sources:)").first()?.data()
            ?: return false

        val srcArr = Regex("""sources:\s*(\[.*?\])""").find(script)?.groupValues?.get(1)
            ?: return false
        AppUtils.parseJson<List<Map<String, String>>>(srcArr).forEach { src ->
            callback(
                ExtractorLink(
                    source  = name,
                    name    = name,
                    url     = src["file"] ?: return@forEach,
                    referer = embed,
                    quality = when (src["label"]) {
                        "1080p" -> Qualities.P1080
                        "720p"  -> Qualities.P720
                        else    -> Qualities.P480
                    }.value,
                    isM3u8  = src["file"]?.endsWith(".m3u8") == true
                )
            )
        }
        return true
    }

    /* ---------------------------------------------------------- */
    /* 6.  UTILS  (reuse Anichi helpers, just re-package)         */
    /* ---------------------------------------------------------- */
    private fun parseAnimeData(json: String) = try {
        parseJson<AnimeData>(json)
    } catch (e: Exception) { null }

    data class AnimeData(
        val images: List<Image>?,
        val episodes: Map<String, Episode>?
    ) {
        data class Image(val coverType: String?, val url: String?)
        data class Episode(val title: Map<String, String>?, val overview: String?, val rating: String?)
    }

    companion object {
        const val baseUrl = "https://ak.sv"
    }
}
