package com.stormunblessed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.stormunblessed.extractors.*

@CloudstreamPlugin
class SeriesKaoProviderPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SeriesKaoProvider())

        // VidHide extractors
        registerExtractorAPI(DhtpreCom())
        registerExtractorAPI(DingtezuniCom())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(VidHideHub())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Peytonepre())
        registerExtractorAPI(VidHidePro())

        // StreamWish extractors
        registerExtractorAPI(SaveFiles())
        registerExtractorAPI(Mwish())
        registerExtractorAPI(Dwish())
        registerExtractorAPI(Ewish())
        registerExtractorAPI(WishembedPro())
        registerExtractorAPI(Kswplayer())
        registerExtractorAPI(Wishfast())
        registerExtractorAPI(Streamwish2())
        registerExtractorAPI(SfastwishCom())
        registerExtractorAPI(Strwish())
        registerExtractorAPI(Strwish2())
        registerExtractorAPI(FlaswishCom())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Obeywish())
        registerExtractorAPI(Jodwish())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(UqloadsXyz())
        registerExtractorAPI(Doodporn())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(Nekowish())
        registerExtractorAPI(Nekostream())
        registerExtractorAPI(Swdyu())
        registerExtractorAPI(Wishonly())
        registerExtractorAPI(Playerwish())
        registerExtractorAPI(Do7go())
        registerExtractorAPI(HlsWish())
        registerExtractorAPI(StreamwishTop())
        registerExtractorAPI(StreamWishExtractor())

        // Byse extractors
        registerExtractorAPI(ByseJikuar())
        registerExtractorAPI(BysezoxexeCom())
        registerExtractorAPI(Bysezejataos())
        registerExtractorAPI(ByseBuho())
        registerExtractorAPI(ByseVepoin())
        registerExtractorAPI(ByseQekaho())
        registerExtractorAPI(ByseSX())
    }
}
