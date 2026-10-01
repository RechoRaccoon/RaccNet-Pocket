package com.mediaviewer.ui

import com.mediaviewer.resources.stellar_logo_vector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.mediaviewer.util.rememberHapticTap

/** The login page's accent: Stellar pink (nobody's profile colors to use yet). */
val LoginPink = Color(0xFFFF4FA1)

/**
 * The sign-in page: black space with stars, the big Stellar logo near the
 * top, and two compact rounded fields (handle, app password) with a pink
 * Sign In button. [drawBackground] false when the caller already draws the
 * starry background behind it (the Hub). [onClose] non-null shows a back
 * button (Dev Tools' preview).
 */
@Composable
fun LoginScreen(
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true,
    onClose: (() -> Unit)? = null
) {
    var handle by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val tap = rememberHapticTap()
    val canSubmit = handle.isNotBlank() && password.isNotBlank() && !isLoading
    fun submit() {
        if (!canSubmit) return
        focusManager.clearFocus()
        onLogin(handle.trim().removePrefix("@"), password)
    }

    Box(modifier.fillMaxSize()) {
        if (drawBackground) SpaceSky(Color.Black, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val logoWidth = (maxWidth * 0.8f).coerceAtMost(380.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(if (drawBackground) rememberTopCutoutClearance() + 56.dp else 40.dp))
                // The Stellar + "Created by Recho Raccoon" lockup the Stellar
                // loading animation uses, with the same soft glow.
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.graphicsLayer { scaleX = 1.04f; scaleY = 1.12f; alpha = 0.55f }
                            .blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            .padding(40.dp)
                    ) {
                        Image(painterResource(com.mediaviewer.resources.Res.drawable.stellar_logo_vector), contentDescription = null, modifier = Modifier.width(logoWidth))
                    }
                    Image(painterResource(com.mediaviewer.resources.Res.drawable.stellar_logo_vector), contentDescription = "Stellar", modifier = Modifier.width(logoWidth))
                }
                Spacer(Modifier.height(36.dp))
                Text(
                    "Sign in with your Bluesky account",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                LoginField(
                    value = handle, onValueChange = { handle = it.trim() },
                    placeholder = "handle.bsky.social", icon = Icons.Default.AlternateEmail,
                    keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                    onImeAction = { runCatching { passwordFocus.requestFocus() } }
                )
                Spacer(Modifier.height(10.dp))
                // iOS: the system's secure-entry keyboard (KeyboardType.Password)
                // misbehaves inside Compose on iOS (dropped/cleared characters,
                // no Paste), so iOS uses a plain ASCII keyboard with autocorrect
                // off — the dots still come from PasswordVisualTransformation —
                // plus a Paste button. Android is unchanged.
                val isIos = com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.IOS
                @Suppress("DEPRECATION")
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                LoginField(
                    value = password, onValueChange = { password = it },
                    placeholder = "App password", icon = Icons.Default.Key,
                    keyboardType = if (isIos) KeyboardType.Ascii else KeyboardType.Password, imeAction = ImeAction.Done,
                    onImeAction = { submit() },
                    password = !showPassword,
                    noAutoCorrect = isIos,
                    modifier = Modifier.focusRequester(passwordFocus),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isIos) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste password",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable {
                                        val pasted = runCatching { clipboard.getText()?.text }.getOrNull()
                                        if (!pasted.isNullOrEmpty()) { tap(); password = pasted.trim() }
                                    }.padding(8.dp)
                                )
                            }
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(34.dp).clip(CircleShape).clickable { showPassword = !showPassword }.padding(8.dp)
                            )
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (canSubmit) Brush.horizontalGradient(listOf(LoginPink, lerp(LoginPink, Color.White, 0.18f)))
                            else Brush.horizontalGradient(listOf(LoginPink.copy(alpha = 0.35f), LoginPink.copy(alpha = 0.35f)))
                        )
                        .clickable(enabled = canSubmit) { tap(); submit() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Sign In", color = Color.White.copy(alpha = if (canSubmit) 1f else 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Use an app password, not your main password.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create an app password",
                    color = lerp(LoginPink, Color.White, 0.25f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { runCatching { uriHandler.openUri("https://bsky.app/settings/app-passwords") } }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(40.dp))
            }
        }
        if (onClose != null) {
            val notchY = rememberNotchCenterY()
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 20.dp)
                    .offset(y = (notchY - 20.dp).coerceAtLeast(4.dp))
                    .size(40.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, LoginPink.copy(alpha = 0.5f), CircleShape)
                    .clickable { tap(); onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A compact rounded login input: icon, text, optional trailing control;
 *  the rim turns pink while it's focused. */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    noAutoCorrect: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        interactionSource = interaction,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(LoginPink),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (noAutoCorrect) KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType, imeAction = imeAction
        ) else KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = keyboardType, imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(shape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (focused) LoginPink.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f), shape)
                    .padding(start = 16.dp, end = if (trailing != null) 6.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = if (focused) LoginPink else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, maxLines = 1)
                    inner()
                }
                if (trailing != null) trailing()
            }
        }
    )
}
