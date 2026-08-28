package com.xdmovies.cloudstream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

/**
 * HubCloud (hubcloud.cx — previously hubcloud.foo) file host.
 *
 * Ported behaviour (from the published XDMovies.cs3 + live checks):
 *  - the base domain rotates; resolve at runtime by following hubcloud.foo's
 *    redirect and fall back to the hardcoded hosts,
 *  - a hubcloud page lists servers (BuzzServer, FSL/FSLv2, Mega, S3,
 *    Pixeldrain, 10Gbps ...) as links (a[href*=hubcloud.php], a.btn,
 *    /api/file/...),
 *  - each server link answers with Location / hx-redirect headers pointing at
 *    the direct file (mp4/mkv) or an m3u8 playlist,
 *  - quality + codec tags are parsed out of the card titles.
 */
class HubCloudExtractor : ExtractorApi() {
    override var name = "Hub-Cloud"
    override var mainUrl = "https://hubcloud.cx"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    )

    companion object {
        private val KNOWN_HOSTS = listOf("https://hubcloud.cx", "https://hubcloud.foo")
        private val QUALITY_REGEX = Regex("""(2160|1080|720|480)\s*p""", RegexOption.IGNORE_CASE)
        private val TAGS = listOf(
            "DOLBYVISION", "HDR10+", "HDR10", "HDR", "HEVC", "H265", "H264", "X265", "X264",
            "DDP", "DD+", "EAC3", "DDP5.1", "AAC", "AC3", "FLAC", "ATMOS", "BLURAY", "BDRIP",
            "BRRIP", "DVDRIP", "HDRIP", "HDTV", "WEB-DL", "WEBRIP", "4K",
        )

        fun serverLabel(text: String): String {
            val t = text.lowercase()
            return when {
                "buzzserver" in t || "buzz" in t -> "BuzzServer"
                "fslv2" in t -> "FSLv2"
                "fsl" in t -> "FSL Server"
                "mega server" in t -> "Mega Server"
                "s3" in t -> "S3 Server"
                "pixeldra" in t -> "Pixeldrain"
                "10gbps" in t -> "10Gbps"
                "gdrive" in t || "drive" in t -> "GDrive"
                else -> "HubCloud"
            }
        }

        fun qualityFrom(text: String?): Int {
            val m = QUALITY_REGEX.find(text ?: "") ?: return Qualities.Unknown.value
            return when (m.groupValues[1].toInt()) {
                2160 -> Qualities.P2160.value
                1080 -> Qualities.P1080.value
                720 -> Qualities.P720.value
                480 -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
        }

        fun tagsFrom(text: String): String {
            val upper = text.uppercase()
            val found = TAGS.filter { upper.contains(it) }
            return if (found.isEmpty()) "" else found.take(3).joinToString(" · ")
        }
    }

    /** Resolve the current hubcloud domain (it rotates). */
    private suspend fun resolveBase(): String {
        runCatching {
            val res = app.get("https://hubcloud.foo", headers = headers)
            val final = res.url.trimEnd('/')
            if (final.startsWith("http") && "hubcloud" in final) return final
        }
        return mainUrl
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = resolveBase()
        val pageUrl = url.replaceHostTo(base)

        val html = runCatching {
            app.get(pageUrl, headers = headers + mapOf("Referer" to (referer ?: base))).text
        }.getOrNull() ?: throw RuntimeException("HubCloud: unable to load $pageUrl")

        val doc = Jsoup.parse(html)

        // Collect the server entries: hubcloud.php links, api/file links and
        // any download buttons, each annotated by its card header when present.
        data class Entry(val href: String, val label: String)

        val entries = mutableListOf<Entry>()
        doc.select("a[href*='hubcloud.php'], a[href*='/api/file/'], a.btn, a.download-button").forEach { a ->
            val href = a.attr("abs:href").takeIf { it.startsWith("http") }
                ?: a.attr("href").takeIf { it.startsWith("http") }
                ?: return@forEach
            val header = a.closest(".card, .download-item, div")?.selectFirst(".card-header, h4, h5, strong")?.text()
            val label = listOfNotNull(header, a.text().takeIf { it.isNotBlank() })
                .joinToString(" ")
                .ifBlank { "HubCloud" }
            if (entries.none { it.href == href }) entries += Entry(href, label)
        }

        if (entries.isEmpty()) {
            // Fall back to whatever direct file links exist on the page.
            doc.select("a[href$='.mp4'], a[href$='.mkv'], a[href$='.m3u8']").forEach { a ->
                val href = a.attr("abs:href").takeIf { it.startsWith("http") } ?: return@forEach
                emit(href, a.text().ifBlank { "HubCloud" }, subtitleCallback, callback)
            }
            return
        }

        entries.forEach { entry ->
            runCatching { emit(entry.href, entry.label, subtitleCallback, callback) }
        }
    }

    /** Follow Location/hx-redirect hops until the direct file link is reached. */
    private suspend fun emit(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var current = url
        var hops = 0
        while (hops++ < 10) {
            val res = runCatching {
                app.get(current, headers = headers + mapOf("Referer" to mainUrl))
            }.getOrNull() ?: return

            val headerRedirect = res.headers["hx-redirect"]?.takeIf { it.isNotBlank() }
                ?: res.headers["HX-Redirect"]?.takeIf { it.isNotBlank() }
                ?: res.headers["location"]?.takeIf { it.isNotBlank() }
            if (headerRedirect != null) {
                current = if (headerRedirect.startsWith("http")) headerRedirect else "$mainUrl$headerRedirect"
                continue
            }

            val finalUrl = res.url.takeIf { it.isNotBlank() } ?: current
            val contentType = res.headers["content-type"]?.lowercase().orEmpty()

            return when {
                contentType.contains("mpegurl") || finalUrl.substringBefore('?').endsWith(".m3u8") -> {
                    // Expand HLS variants so each quality is listed.
                    runCatching {
                        M3u8Helper.generateM3u8(
                            name,
                            finalUrl,
                            mainUrl,
                        ).forEach(callback)
                    }
                    callback(
                        newExtractorLink(name, "$name • ${serverLabel(label)}", finalUrl) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = mainUrl
                            this.quality = qualityFrom(label)
                        }
                    )
                }

                contentType.startsWith("video") || contentType.contains("octet-stream") ||
                    finalUrl.substringBefore('?').endsWith(".mp4") ||
                    finalUrl.substringBefore('?').endsWith(".mkv") -> {
                    callback(
                        newExtractorLink(name, "$name • ${serverLabel(label)}", finalUrl) {
                            this.type = ExtractorLinkType.VIDEO
                            this.referer = mainUrl
                            this.quality = qualityFrom(label)
                        }
                    )
                }

                else -> return // HTML page we cannot parse further
            }
        }
    }

    private fun String.replaceHostTo(base: String): String {
        val oldHost = substringAfter("//").substringBefore('/')
        val newHost = base.substringAfter("//").substringBefore('/')
        return if (oldHost != newHost) replace(oldHost, newHost) else this
    }
}
