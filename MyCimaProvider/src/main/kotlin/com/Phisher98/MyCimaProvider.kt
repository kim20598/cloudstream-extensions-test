package com.wecima

import android.annotation.SuppressLint
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class WecimaProvider : MainProvider() {
    override var mainUrl = "https://wecima.click"
    override var name = "Wecima"
    override val hasMainPage = true
    override var lang = "ar" // Site is in Arabic
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    // Main page sections based on Wecima's structure
    override val mainPage = mainPageOf(
        "/" to "أحدث الأفلام", // Latest Movies
        "/series/" to "أحدث المسلسلات", // Latest Series
        "/trending/" to "الأكثر مشاهدة", // Trending
        "/category/افلام-عربية/" to "أفلام عربية", // Arabic Movies
        "/category/مسلسلات-عربية/" to "مسلسلات عربية" // Arabic Series
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageParam = if (page > 1) "page/$page/" else ""
        val url = "$mainUrl${request.data}$pageParam"
        val document = app.get(url).document
        
        val home = document.select("article.item, div.movie, div.post").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("h2, h3, .title, .name")?.text()?.trim() ?: ""
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: "")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val quality = this.selectFirst(".quality, .hd")?.text()?.trim()
        
        // Determine type based on URL or other indicators
        val type = when {
            href.contains("/series/") || href.contains("/مسلسل") -> TvType.TvSeries
            href.contains("/movie/") || href.contains("/فيلم") -> TvType.Movie
            else -> TvType.Movie
        }

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
            else -> newMovieSearchResponse(title, href) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = encodeUrl(query)
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        
        return document.select("article.item, div.movie, div.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract metadata
        val title = document.selectFirst("h1.entry-title, h1.title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("meta[property=og:image], div.poster img")?.attr("content") ?: ""
        val description = document.selectFirst("div.plot, div.description, .entry-content")?.text()?.trim() ?: ""
        
        // Extract year
        val yearText = document.select("span.date, .year").text()
        val year = yearText.filter { it.isDigit() }.takeLast(4).toIntOrNull()
        
        // Extract genres
        val tags = document.select("div.genres a, .category a").map { it.text().trim() }
        
        // Determine content type
        val isSeries = document.select("div.seasons, .episodes").isNotEmpty() || 
                       url.contains("/series/") || 
                       url.contains("/مسلسل")
        
        if (isSeries) {
            // Extract episodes for series
            val episodes = document.select("div.episodes a, .episode-list a").map { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epName = ep.text().trim()
                val epNumber = epName.filter { it.isDigit() }.toIntOrNull() ?: 0
                
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNumber
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
            }
        } else {
            // Movie load response
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extract video sources - Wecima typically uses iframes
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        val videoSources = document.select("source, video").map { it.attr("src") }
        
        val allSources = mutableListOf<String>()
        
        if (!iframeSrc.isNullOrEmpty()) {
            allSources.add(iframeSrc)
        }
        allSources.addAll(videoSources.filter { it.isNotBlank() })
        
        // Also check for embedded video players
        document.select("div.video-player, .player").forEach { player ->
            player.attr("data-src")?.takeIf { it.isNotBlank() }?.let { allSources.add(it) }
        }
        
        allSources.forEach { source ->
            when {
                source.contains("m3u8") -> {
                    M3u8Helper.generateM3u8(
                        name,
                        source,
                        "$mainUrl/",
                        headers = mapOf("Referer" to mainUrl)
                    ).forEach(callback)
                }
                source.endsWith(".mp4") || source.contains("mp4") -> {
                    callback(
                        ExtractorLink(
                            name,
                            "Direct",
                            source,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value
                        )
                    )
                }
                source.contains("youtube") -> {
                    // Handle YouTube trailers if needed
                }
            }
        }
        
        return allSources.isNotEmpty()
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").split(" ").firstOrNull() ?: ""
            else -> this.attr("abs:src")
        }.takeIf { it.isNotBlank() } ?: this.attr("abs:src")
    }
}