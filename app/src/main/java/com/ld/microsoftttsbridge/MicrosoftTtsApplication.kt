package com.ld.microsoftttsbridge

import android.app.Application
import android.util.Log

/** 两种入口共用的唯一 Microsoft TTS 核心持有者。 */
class MicrosoftTtsApplication : Application() {
    val ttsCore: TranslatorTtsBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TranslatorTtsBridge(log = { message -> Log.i(LOG_TAG, message) })
    }

    override fun onCreate() {
        super.onCreate()
        ttsCore.warmUp()
    }

    override fun onTerminate() {
        ttsCore.shutdown()
        super.onTerminate()
    }

    companion object {
        private const val LOG_TAG = "MicrosoftTtsBridge"
    }
}
