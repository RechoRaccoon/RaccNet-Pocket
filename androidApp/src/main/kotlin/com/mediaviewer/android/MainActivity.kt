package com.mediaviewer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mediaviewer.App

/**
 * Thin Android shell: hosts the shared [App] composable.
 * Everything else (screens, view models, repositories) lives in :shared.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
