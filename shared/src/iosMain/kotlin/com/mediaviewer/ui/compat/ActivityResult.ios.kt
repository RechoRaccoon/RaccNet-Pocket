package com.mediaviewer.ui.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.mediaviewer.platform.PlatformUri

/** iOS pickers: routed to [IosPickers] (PHPicker / document picker). Until
 *  the app shell installs them, launching shows a short message. */
@Suppress("UNCHECKED_CAST")
@Composable
actual fun <I, O> rememberLauncherForActivityResult(
    contract: ResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> {
    val latest = rememberUpdatedState(onResult)
    return remember(contract::class) {
        object : ActivityResultLauncher<I> {
            override fun launch(input: I) {
                val deliver: (Any?) -> Unit = { latest.value(it as O) }
                IosPickers.launch(contract, input, deliver)
            }
        }
    }
}

/** Filled in by the iOS app shell (native pickers). */
object IosPickers {
    var handler: ((contract: ResultContract<*, *>, input: Any?, deliver: (Any?) -> Unit) -> Unit)? = null

    fun launch(contract: ResultContract<*, *>, input: Any?, deliver: (Any?) -> Unit) {
        val h = handler
        if (h != null) { h(contract, input, deliver); return }
        showPlatformToast("Picking files isn't available yet on iOS")
        when (contract) {
            is ActivityResultContracts.PickMultipleVisualMedia -> deliver(emptyList<PlatformUri>())
            is ActivityResultContracts.RequestPermission -> deliver(false)
            else -> deliver(null)
        }
    }
}
