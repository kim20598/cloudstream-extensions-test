package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor

class AkwamProvider : MainAPI() {
    override var mainUrl          = BuildConfig.AKWAM_API
    override var name             = "Akwam"
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
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val isMovie = url.contains("/movie/")
        val title = doc.select("h1.entry-title, h1.movie-title").text()
        val poster= fixUrl(doc.select("div.poster img, div.cover img").attr("src"))
        val plot  = doc.select("div.entry-content p, div.story p").text()
        val year  = doc.select("span.year, span.release-year").text().toIntOrNull()
        val genres= doc.select("div.genres a, div.tags a").map { it.text() }

        /* episodes (TV only) */
        val episodes = if (isMovie) emptyList() else {
            val epDoc = app.get("$mainUrl/ajax/episode/list/${url.split("-").last()}")
                .let { Jsoup.parse(it.text) }
            epDoc.select("ul.episodes > li, div.episode-item").mapIndexed { idx, li ->
                val name = li.select("a, .ep-title").text()
                val href = fixUrl(li.select("a").attr("href"))
                newEpisode(href) {
                    episode = (idx + 1)
                    this.name = name
                }
            }.reversed()
        }

        return (if (isMovie) newMovieLoadResponse(title, url, TvType.Movie) else newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)) {
            posterUrl = poster
            this.plot  = plot
            this.year  = year
            this.tags  = genres
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
        loadExtractor(embed, subtitleCallback, callback)
        return true
    }

    /* ---------- 5.  HOME ROWS (Arabic + English)  ---------- */
    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "أفلام",
        "$mainUrl/series?page=" to "مسلسلات",
        "$mainUrl/anime?page=" to "أنمي",
        "$mainUrl/anime?dub=1&page=" to "أنمي مدبلج",
        "$mainUrl/top?page=" to "الأكثر مشاهدة"
    )
}
