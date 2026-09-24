package org.tomsense.android.ui

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import kotlinx.coroutines.flow.first

/**
 * The user's appearance choice: light/dark, a colour, and a Material style.
 *
 * Global Compose state rather than something each screen reads, because four
 * separate windows draw this app — the activities, the assistant overlay and
 * the feed panel — and a change in Settings should repaint all of them at
 * once without each one watching storage.
 */
object ThemeSettings {
    enum class Mode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

    /**
     * Where the seed colour comes from. [Wallpaper] is Material You proper;
     * the rest are fixed seeds run through the SAME algorithm Android uses for
     * wallpaper palettes, so a preset looks native rather than hand-picked.
     */
    enum class Palette(val label: String, val seed: Long?) {
        Wallpaper("Wallpaper", null),
        Blue("Blue", 0xFF1A73E8),
        Teal("Teal", 0xFF00897B),
        Green("Green", 0xFF2E7D32),
        Purple("Purple", 0xFF6750A4),
        Pink("Pink", 0xFFC2185B),
        Red("Red", 0xFFC62828),
        Orange("Orange", 0xFFEF6C00),
        Amber("Amber", 0xFFF9A825),
    }

    /**
     * The styles offered by the phone's own "Wallpaper & style" picker.
     * [System] only means something with [Palette.Wallpaper]: use exactly the
     * palette the system generated, including whatever style it was set to.
     */
    enum class Style(val label: String) {
        System("Match system"),
        TonalSpot("Tonal spot"),
        Vibrant("Vibrant"),
        Expressive("Expressive"),
        Neutral("Neutral"),
        Rainbow("Rainbow"),
        FruitSalad("Fruit salad"),
        Monochrome("Monochrome"),
    }

    var mode by mutableStateOf(Mode.System)
    var palette by mutableStateOf(Palette.Wallpaper)
    var style by mutableStateOf(Style.System)

    private val Context.themeStore by preferencesDataStore("theme")
    private val MODE = stringPreferencesKey("mode")
    private val PALETTE = stringPreferencesKey("palette")
    private val STYLE = stringPreferencesKey("style")

    suspend fun load(context: Context) {
        val p = context.themeStore.data.first()
        mode = p[MODE]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.System
        palette = p[PALETTE]?.let { runCatching { Palette.valueOf(it) }.getOrNull() } ?: Palette.Wallpaper
        style = p[STYLE]?.let { runCatching { Style.valueOf(it) }.getOrNull() } ?: Style.System
    }

    suspend fun save(context: Context) {
        context.themeStore.edit {
            it[MODE] = mode.name
            it[PALETTE] = palette.name
            it[STYLE] = style.name
        }
    }

    /** The seed a preset or the current wallpaper gives, for previews and schemes. */
    fun seedFor(context: Context, p: Palette): Int? =
        p.seed?.toInt() ?: wallpaperSeed(context)

    private fun wallpaperSeed(context: Context): Int? = runCatching {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()
    }.getOrNull()

    /**
     * The colour scheme to draw with.
     *
     * Wallpaper + Match system is the platform's own dynamic scheme, byte for
     * byte. Every other combination is generated from a seed with the chosen
     * style; with Wallpaper that seed is the wallpaper's primary colour, which
     * is how the system builds its own palettes too.
     */
    fun scheme(context: Context, dark: Boolean): ColorScheme {
        val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (palette == Palette.Wallpaper && style == Style.System) {
            return when {
                dynamic && dark -> dynamicDarkColorScheme(context)
                dynamic -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
        }
        val seed = seedFor(context, palette) ?: Palette.Purple.seed!!.toInt()
        return generate(seed, dark, if (style == Style.System) Style.TonalSpot else style)
    }

    fun generate(seed: Int, dark: Boolean, style: Style): ColorScheme {
        val hct = Hct.fromInt(seed)
        val s: DynamicScheme = when (style) {
            Style.Vibrant -> SchemeVibrant(hct, dark, 0.0)
            Style.Expressive -> SchemeExpressive(hct, dark, 0.0)
            Style.Neutral -> SchemeNeutral(hct, dark, 0.0)
            Style.Rainbow -> SchemeRainbow(hct, dark, 0.0)
            Style.FruitSalad -> SchemeFruitSalad(hct, dark, 0.0)
            Style.Monochrome -> SchemeMonochrome(hct, dark, 0.0)
            Style.System, Style.TonalSpot -> SchemeTonalSpot(hct, dark, 0.0)
        }
        val m = MaterialDynamicColors()
        fun c(dc: com.materialkolor.dynamiccolor.DynamicColor) = Color(dc.getArgb(s))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = c(m.primary()), onPrimary = c(m.onPrimary()),
            primaryContainer = c(m.primaryContainer()), onPrimaryContainer = c(m.onPrimaryContainer()),
            inversePrimary = c(m.inversePrimary()),
            secondary = c(m.secondary()), onSecondary = c(m.onSecondary()),
            secondaryContainer = c(m.secondaryContainer()), onSecondaryContainer = c(m.onSecondaryContainer()),
            tertiary = c(m.tertiary()), onTertiary = c(m.onTertiary()),
            tertiaryContainer = c(m.tertiaryContainer()), onTertiaryContainer = c(m.onTertiaryContainer()),
            background = c(m.background()), onBackground = c(m.onBackground()),
            surface = c(m.surface()), onSurface = c(m.onSurface()),
            surfaceVariant = c(m.surfaceVariant()), onSurfaceVariant = c(m.onSurfaceVariant()),
            surfaceTint = c(m.surfaceTint()),
            inverseSurface = c(m.inverseSurface()), inverseOnSurface = c(m.inverseOnSurface()),
            error = c(m.error()), onError = c(m.onError()),
            errorContainer = c(m.errorContainer()), onErrorContainer = c(m.onErrorContainer()),
            outline = c(m.outline()), outlineVariant = c(m.outlineVariant()), scrim = c(m.scrim()),
            surfaceBright = c(m.surfaceBright()), surfaceDim = c(m.surfaceDim()),
            surfaceContainerLowest = c(m.surfaceContainerLowest()),
            surfaceContainerLow = c(m.surfaceContainerLow()),
            surfaceContainer = c(m.surfaceContainer()),
            surfaceContainerHigh = c(m.surfaceContainerHigh()),
            surfaceContainerHighest = c(m.surfaceContainerHighest()),
        )
    }
}
