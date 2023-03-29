package eu.kanade.tachiyomi.animeextension.pt.animesaria

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class AnimesAria : ParsedAnimeHttpSource() {

    override val name = "Animes Aria"

    override val baseUrl = "https://animesaria.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img")!!.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/novos/animes?page=$page")

    override fun popularAnimeSelector() = "div.item > a"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        TODO("Not yet implemented")
    }

    override fun episodeListSelector(): String {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val row = document.selectFirst("div.anime_background_w div.row")!!
            title = row.selectFirst("h1 > span")!!.text()
            status = row.selectFirst("div.clear span.btn")?.text().toStatus()
            thumbnail_url = document.selectFirst("link[as=image]")!!.attr("href")
            genre = row.select("div.clear a.btn").eachText().joinToString()

            description = buildString {
                document.selectFirst("li.active > small")!!
                    .ownText()
                    .substringAfter(": ")
                    .let(::append)

                append("\n\n")

                row.selectFirst("h1 > small")?.text()?.let {
                    append("Títulos Alternativos: $it\n")
                }

                // Additional info
                row.select("div.pull-right > a").forEach {
                    val title = it.selectFirst("small")!!.text()
                    val value = it.selectFirst("span")!!.text()
                    append("$title: $value\n")
                }
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        TODO("Not yet implemented")
    }

    override fun searchAnimeNextPageSelector(): String? {
        TODO("Not yet implemented")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun searchAnimeSelector(): String {
        TODO("Not yet implemented")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        details.url = "/anime/$id"
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            val ahref = element.selectFirst("a")!!
            title = ahref.attr("title")
            setUrlWithoutDomain(ahref.attr("href").substringBefore("/episodio/"))
        }
    }

    override fun latestUpdatesNextPageSelector() = "a:containsOwn(Próximo):not(.disabled)"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/novos/episodios?page=$page")

    override fun latestUpdatesSelector() = "div.item > div.pos-rlt"

    // ============================= Utilities ==============================
    private fun String?.toStatus() = when (this) {
        "Finalizado" -> SAnime.COMPLETED
        "Lançamento" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
