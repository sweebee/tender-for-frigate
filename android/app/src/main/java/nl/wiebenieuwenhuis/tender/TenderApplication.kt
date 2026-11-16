package nl.wiebenieuwenhuis.tender

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import nl.wiebenieuwenhuis.tender.api.Http
import nl.wiebenieuwenhuis.tender.app.AppModel

class TenderApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        AppModel.init(this)
    }

    /** Images from Frigate load with the app's own HTTP client, so they carry the login cookie. */
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
        .build()

    companion object {
        lateinit var context: Context
            private set
    }
}
