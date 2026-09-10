package com.mediaviewer.platform

import android.app.Application

/**
 * Process-wide [Application] holder for androidMain actuals that need a
 * Context (downloads/MediaStore, notifications, ML Kit, ONNX, settings).
 *
 * Set once by [com.mediaviewer.android.RaccNetApplication.onCreate] before
 * any shared code runs. Using the Application context (never an Activity)
 * avoids leaking UI contexts into long-lived singletons like the tagger
 * or the download pipeline.
 */
object AndroidAppContext {
    lateinit var app: Application
}
