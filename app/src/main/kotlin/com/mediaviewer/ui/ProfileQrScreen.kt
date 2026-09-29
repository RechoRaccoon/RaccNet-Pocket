package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mediaviewer.R
import com.mediaviewer.model.AuthorInfo

/** The profile link a QR code points at. */
fun bskyProfileLink(author: AuthorInfo): String =
    "https://bsky.app/profile/" + author.handle.ifBlank { author.did }

/** A QR code's module grid (true = dark module), without the quiet zone;
 *  high error correction so the avatar in the middle can cover some of it. */
private fun qrMatrix(content: String): Array<BooleanArray>? = runCatching {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
        com.google.zxing.EncodeHintType.MARGIN to 0
    )
    val code = com.google.zxing.qrcode.encoder.Encoder.encode(
        content, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, hints
    )
    val m = code.matrix
    Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
}.getOrNull()

/**
 * A profile's QR code page: the black starry space background, a small
 * Stellar logo near the top, and the profile's bsky.app link as an
 * edge-to-edge QR code in Stellar's own style — rounded dots in the
 * profile's colors (a banner-to-avatar gradient), drawn straight onto the
 * starry sky so the stars show through it, with the profile picture in the
 * middle. Back (top left, under the notch) or the system Back closes it.
 */
@Composable
fun ProfileQrScreen(
    author: AuthorInfo,
    bannerUrl: String?,
    onClose: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val colors = rememberProfileColors(author.did, author.avatarUrl, bannerUrl, bannerKnown = bannerUrl != null, resolve = true)
    // Light enough to read clearly against the dark sky.
    val start = remember(colors) { lerp(colors.banner, Color.White, 0.35f) }
    val end = remember(colors) { lerp(colors.avatar, Color.White, 0.35f) }
    val link = remember(author.handle, author.did) { bskyProfileLink(author) }
    val matrix = remember(link) { qrMatrix(link) }

    Box(Modifier.fillMaxSize().background(Color.Black).blockClicksBehind()) {
        SpaceSky(Color.Black, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val qrSize = maxWidth
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(rememberTopCutoutClearance() + 44.dp))
                Image(
                    painterResource(R.drawable.stellar_logo_vector), contentDescription = "Stellar",
                    modifier = Modifier.width((qrSize * 0.42f).coerceAtMost(220.dp))
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(qrSize), contentAlignment = Alignment.Center) {
                    if (matrix != null) {
                        Canvas(Modifier.matchParentSize()) {
                            val n = matrix.size
                            val quiet = 1
                            val cell = size.width / (n + quiet * 2)
                            val brush = Brush.linearGradient(listOf(start, end), start = Offset.Zero, end = Offset(size.width, size.height))
                            // Space kept clear for the avatar (with a one-dot margin).
                            val logoModules = (n * 0.24f).toInt().let { if (it % 2 == n % 2) it else it + 1 }.coerceAtLeast(5)
                            val logoStart = (n - logoModules) / 2
                            val logoEnd = logoStart + logoModules
                            fun inFinder(x: Int, y: Int) =
                                (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
                            val dot = cell * 0.86f
                            val inset = (cell - dot) / 2f
                            for (y in 0 until n) for (x in 0 until n) {
                                if (!matrix[y][x] || inFinder(x, y)) continue
                                if (x in logoStart until logoEnd && y in logoStart until logoEnd) continue
                                drawRoundRect(
                                    brush = brush,
                                    topLeft = Offset((x + quiet) * cell + inset, (y + quiet) * cell + inset),
                                    size = Size(dot, dot),
                                    cornerRadius = CornerRadius(dot * 0.42f, dot * 0.42f)
                                )
                            }
                            // The three corner "eyes": a rounded ring with a rounded center.
                            listOf(0 to 0, n - 7 to 0, 0 to n - 7).forEach { (fx, fy) ->
                                val ox = (fx + quiet) * cell
                                val oy = (fy + quiet) * cell
                                val ring = cell * 7
                                drawRoundRect(
                                    brush = brush,
                                    topLeft = Offset(ox + cell / 2f, oy + cell / 2f),
                                    size = Size(ring - cell, ring - cell),
                                    cornerRadius = CornerRadius(cell * 2f, cell * 2f),
                                    style = Stroke(width = cell)
                                )
                                drawRoundRect(
                                    brush = brush,
                                    topLeft = Offset(ox + cell * 2f, oy + cell * 2f),
                                    size = Size(cell * 3f, cell * 3f),
                                    cornerRadius = CornerRadius(cell * 1.1f, cell * 1.1f)
                                )
                            }
                        }
                        // The profile picture in the middle.
                        val avatarSize = qrSize * 0.2f
                        Box(
                            Modifier.size(avatarSize).clip(CircleShape)
                                .background(Color.Black)
                                .border(2.dp, Brush.linearGradient(listOf(start, end)), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!author.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = author.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape)
                                )
                            }
                        }
                    } else {
                        Text("Couldn't make a QR code for this profile", color = Color.White.copy(0.7f), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    author.displayName.ifBlank { author.handle }, color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)
                )
                Text(
                    "@${author.handle}", color = lerp(end, Color.White, 0.3f), fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.weight(1.3f))
            }
        }
        RoundBackButton(
            liquidGlass = false, tint = colors.blended, backdrop = null, onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 16.dp, top = rememberTopCutoutClearance() + 4.dp)
        )
    }
}
