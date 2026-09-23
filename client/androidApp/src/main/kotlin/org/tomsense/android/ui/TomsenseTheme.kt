package org.tomsense.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Material You, applied to every surface this app puts on screen.
 *
 * ONE theme for all four entry points — the app, settings, the assistant
 * overlay and the home-screen feed panel. They previously each called a bare
 * `MaterialTheme {}`, which silently means the default purple baseline rather
 * than the user's wallpaper colours; the panel sits directly on the home
 * screen, where looking like a stock Compose sample is most obvious.
 *
 * Dynamic colour is Android 12+. Below that there is no wallpaper palette to
 * read and the baseline scheme is the correct answer, not a degraded one.
 *
 * @param opaque paint [Surface] behind the content. On by default, and the
 * reason light-on-dark mismatches do not come back: the Activity's window
 * background comes from XML and will never match a dynamic scheme exactly, so
 * Compose owns every pixel instead of letting the window show through. Pass
 * `false` for windows that are MEANT to be see-through — the assistant
 * overlay floats a card over whatever app is behind it, and the feed panel
 * animates its own window alpha and paints its own background.
 */
@Composable
fun TomsenseTheme(
    dark: Boolean = isSystemInDarkTheme(),
    opaque: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    // Edge-to-edge is mandatory on targetSdk 35, so the status bar draws over
    // our content and its icons must be told which way to contrast. Only
    // Activities have a window — the overlay services legitimately do not, and
    // skipping them is correct rather than a missed case.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = colors) {
        if (opaque) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        } else {
            content()
        }
    }
}
