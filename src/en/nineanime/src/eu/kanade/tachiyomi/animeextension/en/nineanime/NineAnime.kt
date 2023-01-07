package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class NineAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "9anime"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://9anime.to")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder().add("Referer", baseUrl)
    }

    override fun popularAnimeSelector(): String = "div.ani.items > div"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=trending&page=$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.name").attr("href").substringBefore("?"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li > a[aria-label=pagination.next]"

    override fun episodeListRequest(anime: SAnime): Request {
        val id = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup().selectFirst("div[data-id]").attr("data-id")
        val jsVrfInterceptor = client.newBuilder().addInterceptor(JsVrfInterceptor(id)).build()
        val vrf = jsVrfInterceptor.newCall(GET("$baseUrl/filter")).execute().request.header("url").toString()
        return GET("$baseUrl/ajax/episode/list/$id?vrf=$vrf", headers = Headers.headersOf("url", anime.url))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val episodeElements = document.select(episodeListSelector())
        return episodeElements.map { episodeFromElements(it, animeUrl) }
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    private fun episodeFromElements(element: Element, url: String): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = element.attr("data-sub").toInt().toBoolean()
        val dub = element.attr("data-dub").toInt().toBoolean()
        val jsVrfInterceptor = client.newBuilder().addInterceptor(JsVrfInterceptor(ids)).build()
        val vrf = jsVrfInterceptor.newCall(GET("$baseUrl/filter")).execute().request.header("url").toString()
        episode.url = "/ajax/server/list/$ids?vrf=$vrf&epurl=$url/ep-$epNum"
        episode.episode_number = epNum.toFloat()
        val langPrefix = "[" + if (sub) {
            "Sub"
        } else {
            ""
        } + if (dub) {
            ",Dub"
        } else {
            ""
        } + "]"
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"
        episode.name = "Episode $epNum" + if (sub || dub) {
            ": $langPrefix"
        } else {
            ""
        } + if (name.isNotEmpty() && name != namePrefix) {
            " $name"
        } else {
            ""
        }
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not Used")

    private fun Int.toBoolean() = this == 1

    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url.substringBefore("&epurl")
        val epurl = episode.url.substringAfter("epurl=")
        return GET(baseUrl + url, headers = Headers.headersOf("url", epurl))
    }

    override fun videoListParse(response: Response): List<Video> {
        val epurl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val videoList = mutableListOf<Video>()

        // Sub
        document.select("div[data-type=sub] > ul > li[data-sv-id=41]")
            .firstOrNull()?.attr("data-link-id")
            ?.let { videoList.addAll(extractVideo("Sub", epurl)) }
        // Dub
        document.select("div[data-type=dub] > ul > li[data-sv-id=41]")
            .firstOrNull()?.attr("data-link-id")
            ?.let { videoList.addAll(extractVideo("Dub", epurl)) }
        return videoList
    }

    private fun extractVideo(lang: String, epurl: String): List<Video> {
        val jsInterceptor = client.newBuilder().addInterceptor(JsInterceptor(lang.lowercase())).build()
        val embedLink = jsInterceptor.newCall(GET("$baseUrl$epurl")).execute().request.header("url").toString()
        val jsVizInterceptor = client.newBuilder().addInterceptor(JsVizInterceptor(embedLink)).build()
        val sourceUrl = jsVizInterceptor.newCall(GET(embedLink, headers = Headers.headersOf("Referer", "$baseUrl/"))).execute().request.header("url").toString()
        Log.i("extractVideo", sourceUrl)
        val referer = Headers.headersOf("Referer", embedLink)
        val sourceObject = json.decodeFromString<JsonObject>(
            client.newCall(GET(sourceUrl, referer))
                .execute().body!!.string()
        )
        val mediaSources = sourceObject["data"]!!.jsonObject["media"]!!.jsonObject["sources"]!!.jsonArray
        val masterUrls = mediaSources.map { it.jsonObject["file"]!!.jsonPrimitive.content }
        val masterUrl = masterUrls.find { !it.contains("/simple/") } ?: masterUrls.first()
        val headers = Headers.headersOf("referer", embedLink, "origin", "https://" + masterUrl.toHttpUrl().topPrivateDomain())
        val result = client.newCall(GET(masterUrl, headers)).execute()
        val masterPlaylist = result.body!!.string()
        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p $lang"
                val videoUrl = masterUrl.substringBeforeLast("/") + "/" + it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, headers = headers)
            }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        val lang = preferences.getString("preferred_language", "Sub")
        if (quality != null && lang != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality) && video.quality.contains(lang)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            // If dub is preferred language and anime do not have dub version, respect preferred quality
            if (lang == "Dub" && newList.first().quality.contains("Dub").not()) {
                newList.clear()
                for (video in this) {
                    if (video.quality.contains(quality)) {
                        newList.add(preferred, video)
                        preferred++
                    } else {
                        newList.add(video)
                    }
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("div.poster a").attr("href"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val jsVrfInterceptor = client.newBuilder().addInterceptor(JsVrfInterceptor(query)).build()
        val vrf = jsVrfInterceptor.newCall(GET("$baseUrl/filter")).execute().request.header("url").toString()
        return GET("$baseUrl/filter?keyword=$query&vrf=$vrf&page=$page", headers = Headers.headersOf("Referer", "https://9anime.to/"))
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.title").text()
        anime.genre = document.select("div:contains(Genre) > span > a").joinToString { it.text() }
        anime.description = document.select("div.synopsis > div.shorting > div.content").text()
        anime.author = document.select("div:contains(Studios) > span > a").text()
        anime.status = parseStatus(document.select("div:contains(Status) > span").text())

        // add alternative name to anime description
        val altName = "Other name(s): "
        document.select("h1.title").attr("data-jp")?.let {
            if (it.isBlank().not()) {
                anime.description = when {
                    anime.description.isNullOrBlank() -> altName + it
                    else -> anime.description + "\n\n$altName" + it
                }
            }
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Releasing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter?sort=recently_updated&page=$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("9anime.to", "9anime.id", "9anime.pl")
            entryValues = arrayOf("https://9anime.to", "https://9anime.id", "https://9anime.pl")
            setDefaultValue("https://9anime.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoLanguagePref = ListPreference(screen.context).apply {
            key = "preferred_language"
            title = "Preferred language"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue("Sub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoLanguagePref)
    }
}
