package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*

private const val BSKY_POST_LIMIT = 300

@Composable
fun QuoteRepostDialog(
    target: MediaItem?,
    submitting: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    var text by remember(target.id) { mutableStateOf("") }
    val overLimit = text.length > BSKY_POST_LIMIT

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(min = 200.dp, max = 560.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(OffBlack)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("Quote Repost", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(14.dp))

                    // Growing input — identical behavior to the comment box: no minLines,
                    // capped at 3 rows, OutlinedTextField grows on its own as the user types.
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Add a comment (optional)…", color = DimGray, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White.copy(0.3f), unfocusedBorderColor = Color.White.copy(0.12f),
                            cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp), maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${text.length}/$BSKY_POST_LIMIT",
                        color = if (overLimit) Color(0xFFE0245E) else DimGray,
                        fontSize = 11.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(14.dp))

                    // Preview of the post being quote-reposted
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(0.05f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = target.thumbUrl.ifBlank { target.mediaUrl }, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(target.author.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("@${target.author.handle}", color = DimGray, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { if (!overLimit && !submitting) onSubmit(text.trim()) },
                        enabled = !overLimit && !submitting,
                        colors = ButtonDefaults.buttonColors(containerColor = RepostGreen, disabledContainerColor = Color.White.copy(0.1f)),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        if (submitting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Post", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
