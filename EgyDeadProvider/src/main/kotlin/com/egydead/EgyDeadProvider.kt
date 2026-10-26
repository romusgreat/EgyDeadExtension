package com.egydead

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * CloudStream 3/4 Provider for EgyDead (https://tv10.egydead.live)
 * Provides Movie, TV Series, and Anime streaming with multi-server resolution.
 */
class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv10.egydead.live"
    override var name = "EgyDead"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Standard desktop browser headers to prevent Cloudflare/anti-bot blocks
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/h3/" to "المواضيع المميزة",
        "$mainUrl/%d8%a7%d9%81%d9%84%d8%a7%d9%85/page/" to "أفلام",
        "$mainUrl/%d8%a7%d9%84%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa/page/" to "مسلسلات",
        "$mainUrl/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/page/" to "مسلسلات أنمي",
        "$mainUrl/%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a7%d8%aa/page/" to "أحدث الحلقات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("/page/") -> "${request.data}$page/"
            page > 1 -> "${request.data.removeSuffix("/")}/page/$page/"
            else -> request.data
        }

        val res = app.get(url, headers = defaultHeaders)
        val document = res.document

        val homeItems = document.select(
            ".movie-box, .item, article.post, div.post, .movies-list .box, li.movie, .movie"
        ).mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl, headers = defaultHeaders).document

        return document.select(
            ".movie-box, .item, article.post, div.post, .movies-list .box, li.movie, .movie"
        ).mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return null

        val rawTitle = this.selectFirst("h2, h3, .title, a[title]")?.text()?.trim()
            ?: linkEl.attr("title").trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val title = cleanMediaTitle(rawTitle)
        val imgEl = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgEl?.attr("data-src")?.ifEmpty { null }
                ?: imgEl?.attr("data-lazy-src")?.ifEmpty { null }
                ?: imgEl?.attr("src")?.ifEmpty { null }
        )

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: this.selectFirst(".year, .date")?.text()?.trim()?.toIntOrNull()

        val lowerCheck = "${rawTitle.lowercase()} ${href.lowercase()}"
        val isAnime = lowerCheck.contains("انمي") || lowerCheck.contains("anime")
        val isSeries = lowerCheck.contains("مسلسل") || lowerCheck.contains("series") ||
                lowerCheck.contains("موسم") || lowerCheck.contains("حلقة")

        val tvType = when {
            isAnime -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, headers = defaultHeaders).document

        val rawTitle = document.selectFirst("h1.entry-title, h1, .title, .post-title")?.text()?.trim()
            ?: document.title().substringBefore("-").trim()
        val title = cleanMediaTitle(rawTitle)

        val posterUrl = fixUrlNull(
            document.selectFirst(".poster img, .img-fluid, .post-thumbnail img, meta[property='og:image']")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { it.attr("content") }
            }
        )

        val plot = document.selectFirst(".story, .overview, .entry-content p, .description, .post-content")?.text()?.trim()
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst(".year, a[href*='/years/'], a[href*='/year/']")?.text()?.trim()?.toIntOrNull()

        val tags = document.select(".genres a, .genre a, a[href*='/genre/'], a[href*='/category/'], .taxonomy a").map {
            it.text().trim()
        }

        val rating = document.selectFirst(".rating, .imdb, span[itemprop='ratingValue'], .score")?.text()?.trim()?.toDoubleOrNull()

        val episodeElements = document.select(
            ".episodes-list a, .episodes a, ul.episodes li a, .all-episodes a, a[href*='الحلقة'], a[href*='episode']"
        ).filter {
            val href = it.attr("href")
            href.isNotBlank() && !href.endsWith("/watch/") && !href.equals(fixedUrl, ignoreCase = true)
        }

        val isSeries = episodeElements.isNotEmpty() ||
                rawTitle.contains("مسلسل") ||
                fixedUrl.contains("مسلسل") ||
                rawTitle.contains("موسم")

        return if (isSeries) {
            val seasonMatch = Regex("""الموسم\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            val episodes = if (episodeElements.isNotEmpty()) {
                episodeElements.mapNotNull { el ->
                    val epHref = fixUrl(el.attr("href"))
                    val epText = el.text().trim()
                    val epNum = Regex("""(?:الحلقة|الحلقه|Episode|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find("$epText $epHref")?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    val epSeason = Regex("""(?:الموسم|Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find("$epText $epHref")?.groupValues?.get(1)?.toIntOrNull() ?: seasonMatch

                    Episode(
                        data = epHref,
                        name = epText.ifEmpty { "الحلقة $epNum" },
                        season = epSeason,
                        episode = epNum
                    )
                }.distinctBy { "${it.season}_${it.episode}" }
                 .sortedWith(compareBy({ it.season }, { it.episode }))
            } else {
                listOf(
                    Episode(
                        data = fixedUrl,
                        name = title,
                        season = seasonMatch,
                        episode = Regex("""(?:الحلقة|الحلقه)\s*(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    )
                )
            }

            newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
            }
        } else {
            val watchLink = document.selectFirst("a[href*='/watch/'], a:contains(مشاهدة), a.btn-watch")?.attr("href")
            val targetWatchUrl = when {
                !watchLink.isNullOrBlank() -> fixUrl(watchLink)
                fixedUrl.endsWith("/watch/") || fixedUrl.endsWith("/watch") -> fixedUrl
                else -> "${fixedUrl.removeSuffix("/")}/watch/"
            }

            newMovieLoadResponse(title, fixedUrl, TvType.Movie, targetWatchUrl) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val watchUrl = if (!data.endsWith("/watch/") && !data.endsWith("/watch")) {
            "${data.removeSuffix("/")}/watch/"
        } else {
            data
        }

        val watchDoc = try {
            app.get(watchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            app.get(data, headers = defaultHeaders).document
        }

        val extractedServerUrls = mutableSetOf<String>()

        // 1. Direct iframes in the document
        watchDoc.select("iframe[src], div.video-box iframe, div.player iframe, #player iframe, .embed-responsive-item")
            .forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (isValidVideoHost(src)) {
                    extractedServerUrls.add(src)
                }
            }

        // 2. Server switcher buttons and data attributes
        watchDoc.select("ul.servers li, .server-item, .servers a, [data-url], [data-src], [data-iframe], [data-embed], [data-link]")
            .forEach { element ->
                val candidateAttrs = listOf(
                    element.attr("data-url"),
                    element.attr("data-src"),
                    element.attr("data-iframe"),
                    element.attr("data-embed"),
                    element.attr("data-link"),
                    element.attr("href")
                )

                for (attr in candidateAttrs) {
                    val trimmed = attr.trim()
                    if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("javascript:")) continue

                    if (isValidVideoHost(trimmed)) {
                        extractedServerUrls.add(trimmed)
                        continue
                    }

                    val decoded = decodeBase64Safe(trimmed)
                    if (!decoded.isNullOrBlank()) {
                        if (isValidVideoHost(decoded)) {
                            extractedServerUrls.add(decoded)
                        } else {
                            Regex("""https?://[^\s"'<>]+""").findAll(decoded).forEach { match ->
                                if (isValidVideoHost(match.value)) {
                                    extractedServerUrls.add(match.value)
                                }
                            }
                        }
                    }
                }
            }

        // 3. WordPress AJAX Player handler fallback (admin-ajax.php)
        val postId = watchDoc.selectFirst("[data-post], [data-id], input[name='post_id']")?.let {
            it.attr("data-post").ifEmpty { it.attr("data-id") }.ifEmpty { it.attr("value") }
        }

        if (!postId.isNullOrBlank()) {
            watchDoc.select("[data-server]").forEach { serverEl ->
                val serverId = serverEl.attr("data-server").trim()
                if (serverId.isNotBlank()) {
                    try {
                        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                        val ajaxRes = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to "get_player",
                                "post_id" to postId,
                                "server" to serverId
                            ),
                            headers = defaultHeaders + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to watchUrl
                            )
                        ).text

                        Regex("""https?://[^\s"'<>]+""").findAll(ajaxRes).forEach { match ->
                            if (isValidVideoHost(match.value)) {
                                extractedServerUrls.add(match.value)
                            }
                        }
                    } catch (_: Exception) {
                        // Suppress individual AJAX server timeouts
                    }
                }
            }
        }

        // 4. Resolve extracted hosts through CloudStream's Extractor engine
        for (serverUrl in extractedServerUrls) {
            val fixed = fixUrl(serverUrl)

            if (fixed.contains(".m3u8") || fixed.contains(".mp4") || fixed.contains(".mpd")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = fixed,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = if (fixed.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                foundAny = true
            } else {
                try {
                    val success = loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                    if (success) foundAny = true
                } catch (_: Exception) {
                    // Suppress single-extractor failures
                }
            }
        }

        return foundAny
    }

    private fun isValidVideoHost(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) &&
                !lower.contains("google") &&
                !lower.contains("facebook") &&
                !lower.contains("twitter") &&
                !lower.contains("whatsapp") &&
                !lower.contains("wp-content") &&
                !lower.contains("wp-includes") &&
                !lower.contains("captcha")
    }

    private fun decodeBase64Safe(str: String): String? {
        return try {
            if (str.length > 10 && str.matches(Regex("""^[A-Za-z0-9+/=]+$"""))) {
                val bytes = Base64.getDecoder().decode(str)
                String(bytes, StandardCharsets.UTF_8)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanMediaTitle(title: String): String {
        return title
            .replace(Regex("""(?i)(?:^|\s+)(?:مشاهدة|مشاهده)(?:\s+و\s*(?:تحميل|تنزيل))?(?:\s+(?:فيلم|مسلسل|انمي|أنمي|فلم))?"""), " ")
            .replace(Regex("""(?i)(?:^|\s+)(?:مترجم[ةه]?)(?:\s+(?:كامل[ةه]?|عربي[ةه]?))?"""), " ")
            .replace(Regex("""(?i)(?:^|\s+)(?:مدبلج[ةه]?)(?:\s+(?:كامل[ةه]?|عربي[ةه]?))?"""), " ")
            .replace(Regex("""(?i)(?:^|\s+)(?:اون\s*لاين|أون\s*لاين)"""), " ")
            .replace(Regex("""(?i)(?:^|\s+)(?:بجودة\s+عالية|جودة\s+عالية)"""), " ")
            .replace(Regex("""(?i)\b(?:HD|FHD|1080p|720p|480p|BluRay|WEB-DL|HDRip|TC|CAM|TS)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
