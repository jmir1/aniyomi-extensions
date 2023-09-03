package eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri

import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AsyaAnimeleri : AnimeStream(
    "tr",
    "AsyaAnimeleri",
    "https://asyaanimeleri.com",
) {
    override val animeListUrl = "$baseUrl/series"

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyProtectionInterceptor(network.client))
            .build()
    }
}
