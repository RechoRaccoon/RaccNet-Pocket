package com.mediaviewer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.MediaItem
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.delay

/** Counts already seen this session, so a poll shows its numbers the moment
 *  it comes back on screen (then refreshes). */
private val pollTallyCache = HashMap<String, PollTally>()

/**
 * A Stellar poll in the timeline: the question, then one tappable bar per
 * answer. Picking one posts a reply with just its letter; everyone's letter
 * replies are counted (one per account) and shown as percentages, refreshed
 * every few seconds while the poll is on screen. The counting reads
 * Bluesky's AppView directly, never the PDS.
 */
@Composable
fun PollCard(item: MediaItem, dominantColor: Color, liquidGlass: Boolean, modifier: Modifier = Modifier) {
    val parsed = remember(item.text) { PollFormat.parse(item.text) }
    if (parsed == null) return
    val (question, options) = parsed
    val tap = rememberHapticTap()
    var tally by remember(item.postUri) { mutableStateOf(pollTallyCache[item.postUri]) }
    var localVote by remember(item.postUri) { mutableStateOf(LocalData.pollVote(item.postUri)) }
    var voting by remember(item.postUri) { mutableStateOf(false) }

    LaunchedEffect(item.postUri) {
        while (true) {
            val fresh = runCatching { LocalOverlays.pollTally?.invoke(item.postUri) }.getOrNull()
            if (fresh != null) {
                tally = fresh
                pollTallyCache[item.postUri] = fresh
                if (pollTallyCache.size > 80) pollTallyCache.remove(pollTallyCache.keys.first())
            }
            delay(if (LocalData.batterySaverActive) 20_000 else 6_000)
        }
    }

    val myVote = tally?.myVote ?: localVote
    // A vote just cast counts straight away, before Bluesky has indexed it.
    val counts = remember(tally, localVote) {
        val base = HashMap(tally?.counts ?: emptyMap())
        val pending = localVote
        if (pending != null && tally?.myVote == null) base[pending] = (base[pending] ?: 0) + 1
        base
    }
    val total = counts.values.sum()
    val showResults = myVote != null || total > 0

    Box(modifier.fillMaxSize().padding(top = 104.dp, bottom = 176.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(28.dp)
        val body: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    question, color = Color.White, fontSize = 18.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)
                )
                options.forEachIndexed { index, option ->
                    val letter = PollFormat.letter(index)
                    val count = counts[letter] ?: 0
                    val fraction = if (total > 0) count.toFloat() / total else 0f
                    val shown by animateFloatAsState(if (showResults) fraction else 0f, tween(450), label = "pollBar")
                    val mine = myVote == letter
                    val rowShape = RoundedCornerShape(15.dp)
                    val accent = lerp(dominantColor, Color.White, 0.25f)
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(rowShape)
                            .background(Color.Black.copy(alpha = 0.26f))
                            .border(if (mine) 1.5.dp else 1.dp, accent.copy(alpha = if (mine) 0.95f else 0.4f), rowShape)
                            .clickable(enabled = myVote == null && !voting) {
                                tap()
                                voting = true
                                localVote = letter
                                val vote = LocalOverlays.pollVote
                                if (vote == null) { voting = false; localVote = null }
                                else vote(item, letter) { ok ->
                                    voting = false
                                    if (ok) LocalData.setPollVote(item.postUri, letter) else localVote = null
                                }
                            }
                    ) {
                        // The result bar, behind the label.
                        Box(
                            Modifier.matchParentSize()
                        ) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f))
                                    .background(accent.copy(alpha = if (mine) 0.5f else 0.28f))
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$letter.", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                option, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp,
                                fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (showResults) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${(fraction * 100).toInt()}%", color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    when {
                        total == 0 -> "No votes yet — tap an answer to vote"
                        total == 1 -> "1 vote"
                        else -> "$total votes"
                    } + if (myVote != null) " · You voted $myVote" else "",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.fillMaxWidth(0.94f), shape = shape, tint = dominantColor) { body() }
        } else {
            Box(
                Modifier.fillMaxWidth(0.94f).clip(shape).background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, dominantColor.copy(alpha = 0.5f), shape)
            ) { body() }
        }
    }
}
