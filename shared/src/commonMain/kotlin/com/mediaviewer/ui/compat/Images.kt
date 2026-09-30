package com.mediaviewer.ui.compat

import androidx.compose.ui.graphics.Color
import com.mediaviewer.platform.PlatformContext

/** Loads [url] through the app's image loader at [size]x[size] and returns
 *  the average of its pixels (the "dominant color" every glass surface is
 *  tinted with), or null if it can't be loaded. */
expect suspend fun sampleAverageColor(context: PlatformContext, url: String, size: Int): Color?

/** Coil's own context for this platform (the Android Context itself on
 *  Android; Coil's singleton on iOS). */
expect val PlatformContext.coilContext: coil3.PlatformContext

/** A QR code's module grid (true = dark), no quiet zone, high error
 *  correction. ZXing on Android; [com.mediaviewer.util.QrEncoder] on iOS. */
expect fun qrModuleMatrix(content: String): Array<BooleanArray>
