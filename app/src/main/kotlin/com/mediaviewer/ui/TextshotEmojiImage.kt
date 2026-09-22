package com.mediaviewer.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mediaviewer.util.TextshotRenderer

/**
 * Makes the Textshot's black background see-through.
 *
 * A posted Textshot is an opaque black picture with white text (opaque so it
 * reads correctly in other Bluesky clients, which would otherwise draw white
 * text on a light page). Inside RaccNet's text bubbles we want the bubble
 * itself to show through, so this filter turns each pixel's brightness into
 * its opacity: pure black -> fully transparent, white text and colorful
 * emoji -> fully opaque, with a soft ramp for the anti-aliased edges in
 * between. Colors are left untouched.
 */
val TextshotBlackToClear: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            // alpha = 3.5 * luminance (0.299 R + 0.587 G + 0.114 B), clamped
            1.05f, 2.05f, 0.4f, 0f, 0f
        )
    )
)

/**
 * A Textshot-with-emoji picture, drawn to fill a bubble whose shape has
 * [cornerRadius] rounded corners. Put it inside a bubble that is already
 * sized to the picture's aspect ratio (e.g. `Modifier.aspectRatio(ratio)`).
 *
 * "Rounded corners must never cut off the text": the renderer always leaves
 * [TextshotRenderer.PAD_FRACTION] of empty margin around the ink, so the
 * picture only needs an extra inset when the corner radius is large relative
 * to the bubble. The inset is the exact amount that keeps the ink's own
 * corner inside the arc (a rounded corner of radius r cuts in by
 * r * (1 - 1/sqrt(2)) ~= 0.293 r along the diagonal), which is zero for
 * ordinary bubbles — that renderer margin is intentionally thin now (an
 * edge-to-edge frame, not a padded card), so a bubble with a large corner
 * radius leans on this inset more than it used to.
 */
@Composable
fun TextshotEmojiImage(url: String, cornerRadius: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val minSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val inset = (cornerRadius * 0.293f - minSide * TextshotRenderer.PAD_FRACTION).coerceAtLeast(0.dp)
        AsyncImage(
            model = url,
            contentDescription = "Textshot post with emoji",
            contentScale = ContentScale.Fit,
            colorFilter = TextshotBlackToClear,
            modifier = Modifier.fillMaxSize().padding(inset)
        )
    }
}
