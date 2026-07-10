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
import android.content.pm.LauncherActivityInfo
import android.content.pm.ShortcutInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import com.android.launcher3.ItemInfo
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.google.android.apps.nexuslauncher.DynamicIconProvider

import android.content.ComponentName
import android.content.res.Resources
import ch.deletescape.lawnchair.lawnchairPrefs

class LawnchairIconProvider(private val context: Context) : DynamicIconProvider(context) {

    private val iconPackManager by lazy { IconPackManager.getInstance(context) }

    class ThemeData(val resources: Resources, val packageName: String, val resID: Int) {
        fun loadMonochromeDrawable(accentColor: Int): Drawable {
            val d = resources.getDrawable(resID).mutate()
            d.setTint(accentColor)
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.graphics.drawable.InsetDrawable(d, 0.2f)
            } else {
                val inset = (d.intrinsicWidth * 0.2f).toInt()
                android.graphics.drawable.InsetDrawable(d, inset, inset, inset, inset)
            }
        }
    }

    private var themeMap: Map<ComponentName, ThemeData>? = null

    private fun getThemeMap(): Map<ComponentName, ThemeData> {
        if (themeMap == null) {
            themeMap = createThemedIconMap()
        }
        return themeMap!!
    }

    private fun createThemedIconMap(): Map<ComponentName, ThemeData> {
        val map = HashMap<ComponentName, ThemeData>()
        val lawniconsPkg = "app.lawnchair.lawnicons"
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(lawniconsPkg, 0)
            if (appInfo != null) {
                val resources = pm.getResourcesForApplication(lawniconsPkg)
                val xmlId = resources.getIdentifier("grayscale_icon_map", "xml", lawniconsPkg)
                if (xmlId != 0) {
                    val parser = resources.getXml(xmlId)
                    var type = parser.next()
                    while (type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (type == org.xmlpull.v1.XmlPullParser.START_TAG && "icon" == parser.name) {
                            val pkg = parser.getAttributeValue(null, "package")
                            val cmp = parser.getAttributeValue(null, "component") ?: ""
                            val iconId = parser.getAttributeResourceValue(null, "drawable", 0)
                            if (iconId != 0 && !pkg.isNullOrEmpty()) {
                                map[ComponentName(pkg, cmp)] = ThemeData(resources, lawniconsPkg, iconId)
                            }
                        }
                        type = parser.next()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LawnchairIconProvider", "Unable to parse Lawnicons map.", e)
        }
        return map
    }

    fun getThemeData(componentName: ComponentName): ThemeData? {
        if (!context.lawnchairPrefs.themedIcons) return null
        val map = getThemeMap()
        var td = map[componentName]
        if (td == null) {
            td = map[ComponentName(componentName.packageName, "")]
        }
        return td
    }

    fun getThemedColors(context: Context): IntArray {
        val isDark = ch.deletescape.lawnchair.theme.ThemeManager.getInstance(context).isDark
        val accent = ch.deletescape.lawnchair.colors.ColorEngine.getInstance(context).accent
        if (isDark) {
            val bg = android.graphics.Color.parseColor("#1C1B1F")
            return intArrayOf(bg, accent)
        } else {
            val fg = android.graphics.Color.parseColor("#1C1B1F")
            return intArrayOf(accent, fg)
        }
    }

    override fun getIcon(launcherActivityInfo: LauncherActivityInfo, iconDpi: Int, flattenDrawable: Boolean): Drawable {
        if (context.lawnchairPrefs.drawerThemedIcons) {
            val td = getThemeData(launcherActivityInfo.componentName)
            if (td != null) {
                val colors = getThemedColors(context)
                val fg = td.loadMonochromeDrawable(colors[1])
                return ThemedIconDrawable(android.graphics.drawable.ColorDrawable(colors[0]), fg)
            }
        }
        return iconPackManager.getIcon(launcherActivityInfo, iconDpi, flattenDrawable, null, this).assertNotAdaptiveIconDrawable(launcherActivityInfo)
    }

    fun getIcon(launcherActivityInfo: LauncherActivityInfo, itemInfo: ItemInfo, iconDpi: Int, flattenDrawable: Boolean): Drawable {
        if (context.lawnchairPrefs.themedIcons) {
            val td = getThemeData(launcherActivityInfo.componentName)
            if (td != null) {
                val colors = getThemedColors(context)
                val fg = td.loadMonochromeDrawable(colors[1])
                return ThemedIconDrawable(android.graphics.drawable.ColorDrawable(colors[0]), fg)
            }
        }
        return iconPackManager.getIcon(launcherActivityInfo, iconDpi, flattenDrawable, itemInfo, this).assertNotAdaptiveIconDrawable(launcherActivityInfo)
    }

    fun getIcon(shortcutInfo: ShortcutInfo, iconDpi: Int): Drawable? {
        return iconPackManager.getIcon(shortcutInfo, iconDpi).assertNotAdaptiveIconDrawable(shortcutInfo)
    }

    fun getDynamicIcon(launcherActivityInfo: LauncherActivityInfo?, iconDpi: Int, flattenDrawable: Boolean): Drawable {
        return super.getIcon(launcherActivityInfo, iconDpi, flattenDrawable).assertNotAdaptiveIconDrawable(launcherActivityInfo)
    }

    private fun <T> T.assertNotAdaptiveIconDrawable(info: Any?): T {
        if (Utilities.ATLEAST_OREO && this is AdaptiveIconDrawable) {
            error("unwrapped AdaptiveIconDrawable for ${
                if (info is LauncherActivityInfo) info.applicationInfo else info
            }")
        }
        return this
    }

    companion object {

        @JvmStatic
        fun getAdaptiveIconDrawableWrapper(context: Context): AdaptiveIconCompat {
            return AdaptiveIconCompat.wrap(context.getDrawable(
                    R.drawable.adaptive_icon_drawable_wrapper)!!.mutate()) as AdaptiveIconCompat
        }
    }
}
