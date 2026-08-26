package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.tagging.TaggerModelManager
import com.mediaviewer.ui.theme.*
import com.mediaviewer.viewmodel.MainViewModel
import kotlin.math.roundToInt

/** AI Tagging feature: the full-screen overlay shown while "Locally Tag All
 *  Liked Posts" runs — per the request, this doesn't go away until tagging
 *  is complete (its own BackHandler swallows the system back gesture rather
 *  than letting it dismiss mid-run; [onDismiss] itself still lets the
 *  person cancel via the explicit button, same as e.g. the download-all-
 *  liked flow elsewhere in the app already allows).
 *
 *  Uses the same profile-color-reflected background gradient as every other
 *  full-screen page (Search, Hub, DM inbox) — see rememberDominantColor/
 *  postBackgroundBrush in GlassTheme.kt. */
@Composable
fun TaggingOverlay(
    state: MainViewModel.TaggingUiState,
    liquidGlass: Boolean,
    selfAvatarUrl: String? = null,
    onDismiss: () -> Unit,
    onSearchLiked: () -> Unit
) {
    // Only intercept back to *cancel* an in-flight run, asking for the tap
    // that already exists (the Cancel button) rather than letting a stray
    // back-gesture silently kill a long-running pass; once the run is
    // finished there's nothing left to protect, so back behaves normally.
    if (state.isRunning) {
        androidx.activity.compose.BackHandler(onBack = {})
    } else {
        androidx.activity.compose.BackHandler(onBack = onDismiss)
    }

    val profileTint = if (selfAvatarUrl != null) rememberDominantColor(selfAvatarUrl) else NeutralGlassTint

    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize().blockClicksBehind()) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                .then(
                    if (liquidGlass) Modifier.background(postBackgroundBrush(profileTint)).drawWithContent {
                        backdropLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    } else Modifier.background(OledBlack)
                )
        )

        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            val cardShape = RoundedCornerShape(24.dp)
            @Composable
            fun CardContent() {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val modelState = state.modelState
                    when {
                        state.errorMessage != null -> {
                            Text("Tagging Failed", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(state.errorMessage, color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(18.dp))
                            DismissButton(liquidGlass, profileTint, "Close", onDismiss)
                        }
                        state.isComplete -> {
                            Box(Modifier.size(56.dp).clip(CircleShape).background(VoteGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = VoteGreen, modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Tagging Complete", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("${state.tagged} of ${state.scanned} liked posts tagged · ${formatBytes(state.datasetBytes)}", color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(20.dp))
                            DismissButton(liquidGlass, profileTint, "Search Liked Posts", onSearchLiked)
                        }
                        modelState is TaggerModelManager.State.Downloading -> {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.height(14.dp))
                            Text("Downloading Tagging Model", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            val total = modelState.totalBytes
                            val subtitle = if (total > 0) "${formatBytes(modelState.bytesDownloaded)} of ${formatBytes(total)}" else "Starting…"
                            Text(subtitle, color = DimGray, fontSize = 12.sp)
                            Spacer(Modifier.height(2.dp))
                            Text("One-time download — the model stays on your device from now on", color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(20.dp))
                            DismissButton(liquidGlass, profileTint, "Cancel", onDismiss)
                        }
                        else -> {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Tagging Liked Posts…", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${state.scanned} scanned", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                                Text("  ·  ", color = DimGray, fontSize = 13.sp)
                                Text("${state.tagged} tagged", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                                Text("  ·  ", color = DimGray, fontSize = 13.sp)
                                Text(formatBytes(state.datasetBytes), color = DimGray, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(18.dp))
                            DismissButton(liquidGlass, profileTint, "Cancel", onDismiss)
                        }
                    }
                }
            }
            if (liquidGlass) {
                LiquidGlassSurface(Modifier.fillMaxWidth(0.82f), shape = cardShape, tint = profileTint, backdrop = backdrop) { CardContent() }
            } else {
                Box(Modifier.fillMaxWidth(0.82f).clip(cardShape).background(Color.White.copy(0.06f))) { CardContent() }
            }
        }
    }
}

@Composable
private fun DismissButton(liquidGlass: Boolean, tint: Color, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = tint) else Modifier.clip(shape).background(tint.copy(alpha = 0.35f)))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "${(bytes / 1000.0).roundToInt()} KB"
    else -> "$bytes B"
}
