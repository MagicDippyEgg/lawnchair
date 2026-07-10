/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.deletescape.lawnchair.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import ch.deletescape.lawnchair.colors.ColorEngine
import ch.deletescape.lawnchair.lawnchairPrefs
import ch.deletescape.lawnchair.theme.ThemeManager
import ch.deletescape.lawnchair.toBitmap
import com.android.launcher3.FastBitmapDrawable
import com.android.launcher3.LauncherAppState
import com.android.launcher3.Utilities

/**
 * A best-effort "Material You" style themed icon for this branch's API 29
 * target.
 *
 * The real themed icon feature (shipped in newer Lawnchair) relies on
 * `AdaptiveIconDrawable#getMonochrome()`, added in Android 13 (API 33), which
 * does not exist here. This is an approximation built entirely from things
 * already available on API 25+:
 *
 *  - The color comes from [ColorEngine.Resolvers.THEMED_ICON], which reuses
 *    the app's existing wallpaper-color-extraction and color-picker system
 *    (same one used for accent/dock colors) - so it supports "auto from
 *    wallpaper" as well as picking an accent/system/custom color.
 *  - The "monochrome" silhouette is approximated from the icon's own alpha
 *    channel rather than a real OS-provided monochrome layer.
 *
 * Caveat: this works well for icons that are a glyph on a transparent
 * background (most adaptive icon foregrounds, most icon-pack icons). It
 * works poorly for icons that are fully opaque bitmaps with little to no
 * transparency (e.g. some photo-style icons), since there's no alpha
 * information to build a silhouette from. In that case [themeIfEnabled]
 * deliberately bails out and returns the original icon rather than draw an
 * ugly solid-color blob.
 */
object ThemedIconDrawable {

    // Alpha values below this are treated as fully transparent when building the silhouette.
    private const val SILHOUETTE_ALPHA_THRESHOLD = 20

    // If more than this fraction of pixels are opaque, there's no real silhouette to extract.
    private const val MAX_OPAQUE_FRACTION = 0.92f

    @JvmStatic
    fun isEnabled(context: Context) = context.lawnchairPrefs.themedIcons

    /**
     * Returns a themed version of [baseIcon] if themed icons are enabled and
     * a reasonable silhouette could be extracted, otherwise returns
     * [baseIcon] unchanged.
     */
    @JvmStatic
    fun themeIfEnabled(context: Context, baseIcon: Drawable): Drawable {
        if (!isEnabled(context)) return baseIcon
        return try {
            themeIcon(context, baseIcon) ?: baseIcon
        } catch (e: Exception) {
            // A theming failure should never take down icon loading.
            baseIcon
        }
    }

    private fun themeIcon(context: Context, baseIcon: Drawable): Drawable? {
        val resolveInfo = ColorEngine.getInstance(context).resolveColor(ColorEngine.Resolvers.THEMED_ICON)
        val accent = resolveInfo.color
        val isDark = ThemeManager.getInstance(context).isDark

        // Muted tone for the background, stronger tone for the silhouette -
        // loosely mirrors Material You's tonal-container / on-container pairing.
        val backgroundColor = blend(accent, if (isDark) Color.BLACK else Color.WHITE, if (isDark) 0.72f else 0.82f)
        val foregroundColor = blend(accent, if (isDark) Color.WHITE else Color.BLACK, 0.18f)

        val iconSize = LauncherAppState.getIDP(context).iconBitmapSize
        val sourceBitmap = baseIcon.toBitmap(fallbackSize = iconSize) ?: return null
        val silhouette = extractSilhouette(sourceBitmap, foregroundColor) ?: return null

        val foregroundDrawable = FastBitmapDrawable(silhouette)
        return if (Utilities.ATLEAST_OREO) {
            // Adaptive icon foregrounds are normally drawn oversized and then
            // center-cropped by the OS. Our silhouette is already icon-sized,
            // so inset it slightly to sit correctly within the adaptive bounds.
            AdaptiveIconCompat(ColorDrawable(backgroundColor), InsetDrawable(foregroundDrawable, 0.18f))
        } else {
            // Pre-Oreo devices have no concept of adaptive icons; draw the
            // silhouette directly over a solid-color square instead.
            LegacyThemedIconDrawable(backgroundColor, foregroundDrawable)
        }
    }

    /**
     * Builds a solid-tint silhouette from [source]'s alpha channel. Returns
     * null if the source doesn't look like it has a usable silhouette (i.e.
     * it's mostly or fully opaque).
     */
    private fun extractSilhouette(source: Bitmap, tint: Int): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        var opaqueCount = 0
        val tintRgb = tint and 0x00FFFFFF
        for (i in pixels.indices) {
            val alpha = (pixels[i] ushr 24) and 0xFF
            if (alpha < SILHOUETTE_ALPHA_THRESHOLD) {
                pixels[i] = 0
            } else {
                pixels[i] = (alpha shl 24) or tintRgb
                opaqueCount++
            }
        }

        if (opaqueCount > pixels.size * MAX_OPAQUE_FRACTION) return null

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun blend(color: Int, with: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        val a = Color.alpha(color) * inverse + Color.alpha(with) * ratio
        val r = Color.red(color) * inverse + Color.red(with) * ratio
        val g = Color.green(color) * inverse + Color.green(with) * ratio
        val b = Color.blue(color) * inverse + Color.blue(with) * ratio
        return Color.argb(a.toInt(), r.toInt(), g.toInt(), b.toInt())
    }

    /** Solid-color square background + tinted silhouette, for pre-Oreo devices. */
    private class LegacyThemedIconDrawable(
        backgroundColor: Int,
        private val foreground: Drawable
    ) : Drawable() {

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                backgroundPaint
            )
            foreground.bounds = bounds
            foreground.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            backgroundPaint.alpha = alpha
            foreground.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            foreground.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.OPAQUE"))
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
