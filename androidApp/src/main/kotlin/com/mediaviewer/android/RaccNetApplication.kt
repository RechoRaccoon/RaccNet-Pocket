package com.mediaviewer.android

import android.app.Application
import com.mediaviewer.platform.AndroidAppContext

/**
 * Thin Android shell. All app logic lives in :shared (commonMain);
 * this Application exists only so platform actuals that need a Context
 * (downloads, notifications, ML Kit, ONNX) have one to grab.
 */
class RaccNetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        // Platform actuals' context holder (see platform/AndroidContext.kt).
        AndroidAppContext.app = this
    }

    companion object {
        /** Application context for androidMain actuals. Set in onCreate. */
        lateinit var appContext: Application
            private set
    }
}
