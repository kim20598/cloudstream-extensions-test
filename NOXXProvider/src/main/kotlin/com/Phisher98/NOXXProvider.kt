package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.DdosGuardKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody
import org.jsoup.nodes.Element

class NOXXProvider : MainAPI() {
    override var mainUrl = "https://noxx.to"
    override var name = "NOXX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )
    private var ddosGuardKiller = DdosGuardKiller(true)

    // API call for main page (paginated)
    private suspend fun queryTVApi(count: Int, query: String): AppResponse {
        val body = FormBody.Builder()
            .addEncoded("no", "$count")
            .addEncoded("gpar", query)
            .addEncoded("qpar", "")
            .addEncoded("spar", "series_added_date desc")
            .build()

        return app.post(
            "$mainUrl/fetch.php",
            requestBody = body,
            interceptor = ddosGuardKiller,
            referer = "$mainUrl/"
        )
    }

    // API call for search
    private suspend fun queryTVsearchApi(query: String): AppResponse {
        return app.post(
            "$mainUrl/livesearch.php",
            data = mapOf(
                "searchVal" to query
            ),
            interceptor = ddosGuardKiller,
            referer = "$mainUrl/"
        )
    }

    // Define main page categories
    private val scifiShows = "Sci-Fi"
    private val advenShows = "Adventure"
    private val actionShows = "Action"
    private val dramaShows = "Drama"
    private val comedyShows = "Comedy"
    private val fantasyShows = "Fantasy"

    override val mainPage = mainPageOf(
        scifiShows to scifiShows,
        advenShows to advenShows,
        actionShows to actionShows,
        comedyShows to comedyShows,
        fantasyShows to fantasyShows,
        dramaShows to dramaShows,
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val itemsPerPage = 48 // Confirm this number from the website
        val offset = page * itemsPerPage
        val category = request.data

        val document = queryTVApi(offset, category).document
        val items = document.select("a.block").mapNotNull { it.toSearchResult() }

        // Check if this is the last page: if we got fewer items than the page limit
        val hasNext = items.size >= itemsPerPage

        return HomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    // Helper function to convert an HTML element to a SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div > div > span")?.text()?.trim() ?: return null
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val quality = SearchQuality.HD

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = queryTVsearchApi(query).document
        return document.select("a[href^=\"/tv\"]").mapNotNull {
            val title = it.selectFirst("div > h2")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl("$mainUrl${it.attr("href")}")
            val posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = SearchQuality.HD
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = ddosGuardKiller).document

        val title = doc.selectFirst("h1.px-5")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst("img.relative")?.attr("src"))
        val tags = doc.select("div.relative a[class*=\"py-0.5\"]").map { it.text() }
        val description = doc.selectFirst("p.leading-tight")?.text()?.trim()
        val rating = doc.select("span.text-xl").text().toRatingInt()
        val actors = doc.select("div.font-semibold span.text-blue-300").map { it.text() }
        val recommendations = doc.select("a.block").mapNotNull { it.toSearchResult() }

        val numberRegex = Regex("\\d+")
        val episodes = ArrayList<Episode>()

        doc.select("section.container > div.border-b").forEach { seasonElement ->
            val seasonText = seasonElement.select("button > span").text()
            val seasonNumber = numberRegex.find(seasonText)?.value?.toInt()

            seasonElement.select("div.season-list > a").forEach { episodeElement ->
                val episodeHref = mainUrl + episodeElement.attr("href")
                val episodeText = episodeElement.select("span.flex").text()
                val episodeNumber = numberRegex.find(episodeText)?.value?.toInt()
                val episodeName = episodeElement.ownText().trim().removePrefix("Episode ")

                episodes.add(
                    Episode(
                        data = episodeHref,
                        name = episodeName,
                        season = seasonNumber,
                        episode = episodeNumber
                    )
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.rating = rating
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    // **CRITICALLY UPDATED FUNCTION**
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, interceptor = ddosGuardKiller).document

        // Find all iframes that likely contain the video players
        val iframes = doc.select("iframe[src]").map { it.attr("src").trim() }.filter { it.isNotBlank() }

        if (iframes.isEmpty()) {
            // Log a warning if no iframes are found for debugging
            return false
        }

        // For each iframe found, let CloudStream's extractor system handle it.
        // This will automatically support Dood, Streamtape, Fembed, etc.
        iframes.forEach { iframeUrl ->
            loadExtractor(iframeUrl, url, subtitleCallback, callback)
        }

        // Return true indicating we found potential sources to process.
        // The extractors will call the 'callback' function asynchronously for each valid link they find.
        return true
    }
}
