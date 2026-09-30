package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown instead of the app after a crash: the error as copyable text
 *  (no Android Studio / Xcode in this workflow, so this is how a crash
 *  gets reported). */
@Composable
fun CrashLogScreen(log: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)
    ) {
        Text("Stellar crashed last time it ran", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Copy this and send it back for a fix.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.background(Color(0xFF2A7D46)).clickable { clipboard.setText(AnnotatedString(log)) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text("Copy", color = Color.White, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text("Dismiss", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(log, color = Color(0xFF8BE28B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
