package com.AkSv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AkSvProvider : MainAPI() {
    override var mainUrl              = "https://ak.sv"
    override var name                 = "AkSv"
    override val hasMainPage          = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.OVA)

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get("$mainUrl/latest?page=$page").document
        val items = doc.select("div.anime-card").map { card ->
            newAnimeSearchResponse(
                card.select("h3.title").text(),
                fixUrl(card.select("a").attr("href"))
            ) {
                posterUrl = fixUrl(card.select("img").attr("src"))
            }
        }
        return newHomePageResponse("Latest", items)
    }

    override suspend fun search(query: String) =
        app.get("$mainUrl/search?q=$query").document
            .select("div.anime-card").map { card ->
                newAnimeSearchResponse(
                    card.select("h3").text(),
                    fixUrl(card.select("a").attr("href"))
                )
            }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = doc.select("ul.episodes > li").map { li ->
            Episode(
                fixUrl(li.select("a").attr("href")),
                li.select("a").text()
            )
        }.reversed()

        return newAnimeLoadResponse(
            doc.select("h1.anime-title").text(),
            url,
            TvType.Anime,
            episodes
        ) {
            posterUrl = fixUrl(doc.select("img.poster").attr("src"))
            plot      = doc.select("p.synopsis").text()
        }
    }

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
}
