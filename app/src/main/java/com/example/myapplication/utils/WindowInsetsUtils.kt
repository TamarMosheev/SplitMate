package com.example.myapplication.utils

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Light system bars, and pads [root] for the status bar, navigation bar and keyboard. */
fun Activity.applyLightSystemBarsAndInsets(root: View) {
    WindowCompat.getInsetsController(window, root).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        )
        view.updatePadding(top = bars.top, bottom = bars.bottom)
        insets
    }
}
