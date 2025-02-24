package com.arn.scrobble.pref

import android.content.Context
import androidx.core.content.edit
import com.arn.scrobble.widget.ChartsWidgetListItem
import com.frybits.harmony.getHarmonySharedPreferences
import com.google.android.material.color.DynamicColors
import hu.autsoft.krate.*
import hu.autsoft.krate.default.withDefault
import hu.autsoft.krate.kotlinx.kotlinxPref

class WidgetPrefs(context: Context) {

    val sharedPreferences = context.getHarmonySharedPreferences(NAME)

    operator fun get(widgetId: Int) = SpecificWidgetPrefs(widgetId)

    fun chartsData(tab: Int, period: String) = ChartsData(tab, period)

    inner class ChartsData(tab: Int, period: String) : Krate {
        override val sharedPreferences = this@WidgetPrefs.sharedPreferences

        var dataJson by kotlinxPref<List<ChartsWidgetListItem>>("${tab}_$period")
    }

    inner class SpecificWidgetPrefs(private val widgetId: Int) : Krate {
        override val sharedPreferences = this@WidgetPrefs.sharedPreferences

        var tab by intPref(PREF_WIDGET_TAB.prefName)
        var bgAlpha by floatPref(PREF_WIDGET_BG_ALPHA.prefName).withDefault(0.6f)
        var theme by intPref(PREF_WIDGET_THEME.prefName).withDefault(
            if (DynamicColors.isDynamicColorAvailable())
                WidgetTheme.DYNAMIC.ordinal
            else
                WidgetTheme.DARK.ordinal
        )

        var period by stringPref(PREF_WIDGET_PERIOD.prefName)
        var periodName by stringPref(PREF_WIDGET_PERIOD_NAME.prefName)
        var shadow by booleanPref(PREF_WIDGET_SHADOW.prefName).withDefault(true)
        var lastUpdated by longPref(PREF_WIDGET_LAST_UPDATED.prefName)

        fun clear() {
            sharedPreferences.edit {
                sharedPreferences.all
                    .keys
                    .toList()
                    .forEach { key ->
                        if (key.endsWith("_$widgetId"))
                            remove(key)
                    }
            }
        }

        private val String.prefName inline get() = "${this}_$widgetId"
    }

    companion object {
        const val NAME = "widget"
        const val PREF_WIDGET_TAB = "tab"
        const val PREF_WIDGET_BG_ALPHA = "bg_alpha"
        const val PREF_WIDGET_THEME = "theme"
        const val PREF_WIDGET_PERIOD = "period_key"
        const val PREF_WIDGET_PERIOD_NAME = "period_name"
        const val PREF_WIDGET_SHADOW = "shadow"
        const val PREF_WIDGET_LAST_UPDATED = "last_updated"
    }
}

enum class WidgetTheme {
    LIGHT, DARK, DYNAMIC
}