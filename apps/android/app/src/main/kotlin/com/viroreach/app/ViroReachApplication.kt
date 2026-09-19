package com.viroreach.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.viroreach.app.session.SessionManager

class ViroReachApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.viroreach.app.diagnostics.CrashReporter.install(this)
        SessionManager.get(this)
    }

    /** One image loader for the app, able to play animated GIFs in chat. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
