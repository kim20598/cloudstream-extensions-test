package com.aksv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AkSvProvider : MainAPI() {
    override var mainUrl              = BuildConfig.AKSV_API
    override var name                 = "AkSv"
    override val hasMainPage          = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.OVA)

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
            ){
                posterUrl = fixUrl(card.select("img").attr("src"))
            }
        }
    }

    /* 3.  DETAIL  */
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h1.anime-title").text()
        val plot  = doc.select("p.synopsis").text()
        val poster= fixUrl(doc.select("img.poster").attr("src"))

        val episodes = doc.select("ul.episodes > li").map { li ->
            val name = li.select("a").text()
            val href = fixUrl(li.select("a").attr("href"))
            newEpisode(href){ episode = name.toIntOrNull() ?: 1 }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    /* 4.  LINKS  */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = fixUrl(
            app.get(data).document.select("iframe#player").attr("src")
        )
        val script = app.get(embed, referer = data).document
            .select("script:containsData(sources:)").first()?.data()
            ?: return false
        val srcArr = Regex("""sources:\s*(\[.*?\])""").find(script)?.groupValues?.get(1)
            ?: return false
        AppUtils.parseJson<List<Map<String,String>>>(srcArr).forEach { src ->
            callback(
                newExtractorLink(name, name, src["file"] ?: return@forEach, embed) {
                    quality = when (src["label"]) {
                        "1080p" -> Qualities.P1080
                        "720p"  -> Qualities.P720
                        else    -> Qualities.P480
                    }.value
                    isM3u8 = src["file"]?.endsWith(".m3u8") == true
                }
            )
        }
        return true
    }
}
