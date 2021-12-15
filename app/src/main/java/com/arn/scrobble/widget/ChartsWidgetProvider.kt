package com.arn.scrobble.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.arn.scrobble.MainActivity
import com.arn.scrobble.NLService
import com.arn.scrobble.R
import com.arn.scrobble.Stuff
import com.arn.scrobble.pref.WidgetPrefs
import com.arn.scrobble.pref.WidgetTheme


class ChartsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val prefs = WidgetPrefs(context)

        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, prefs[appWidgetId])
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
        ChartsWidgetUpdaterJob.cancel(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = WidgetPrefs(context)
        appWidgetIds.forEach { prefs[it].clear() }

    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (NLService.iUPDATE_WIDGET == intent.action) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID)
                return
            val appWidgetManager = AppWidgetManager.getInstance(context)

            val prefs = WidgetPrefs(context)[appWidgetId]
            val tab = intent.getIntExtra(WidgetPrefs.PREF_WIDGET_TAB, -1)
            if (tab != -1) {
                prefs.tab = tab
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.appwidget_list)
            }
            updateAppWidget(context, appWidgetManager, appWidgetId, prefs)
        }
    }
}

internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    prefs: WidgetPrefs.SpecificWidgetPrefs
) {

    // Here we setup the intent which points to the StackViewService which will
    // provide the views for this collection.

    val tab = prefs.tab ?: Stuff.TYPE_ARTISTS
    val period = prefs.period
    val bgAlpha = prefs.bgAlpha
    val theme = WidgetTheme.values()[prefs.theme]
    val hasShadow = prefs.shadow

    val layoutId = when {
        theme == WidgetTheme.DARK && hasShadow -> R.layout.appwidget_charts_dark_shadow
        theme == WidgetTheme.DARK && !hasShadow -> R.layout.appwidget_charts_dark
        theme == WidgetTheme.LIGHT && hasShadow -> R.layout.appwidget_charts_light_shadow
        theme == WidgetTheme.LIGHT && !hasShadow -> R.layout.appwidget_charts_light
        theme == WidgetTheme.DYNAMIC && hasShadow -> R.layout.appwidget_charts_dynamic_shadow
        theme == WidgetTheme.DYNAMIC && !hasShadow -> R.layout.appwidget_charts_dynamic
        else -> R.layout.appwidget_charts_dark_shadow
    }
    val rv = RemoteViews(context.packageName, layoutId)

    if (period != null) {
        val intent = Intent(context, ChartsListService::class.java)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

        // When intents are compared, the extras are ignored, so we need to embed the extras
        // into the data so that the extras will not be ignored.
        intent.data = Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))

        rv.setRemoteAdapter(R.id.appwidget_list, intent)
    }
    // The empty view is displayed when the collection has no items. It should be a sibling
    // of the collection view.
    rv.setEmptyView(R.id.appwidget_list, R.id.appwidget_status)


    if (period == null || WidgetPrefs(context).chartsData(tab, period).data == null)
        rv.setInt(R.id.appwidget_status, "setText", R.string.appwidget_loading)
    else
        rv.setInt(R.id.appwidget_status, "setText", R.string.charts_no_data)


    // Here we setup the a pending intent template. Individuals items of a collection
    // cannot setup their own pending intents, instead, the collection as a whole can
    // setup a pending intent template, and the individual items can set a fillInIntent
    // to create unique before on an item to item basis.
    val infoIntent = Intent(context, MainActivity::class.java)
    val infoPendingIntent = PendingIntent.getActivity(
        context, 0, infoIntent,
        Stuff.updateCurrentOrMutable
    )
    rv.setPendingIntentTemplate(R.id.appwidget_list, infoPendingIntent)

    val tabIntent = Intent(context, ChartsWidgetProvider::class.java)
    tabIntent.action = NLService.iUPDATE_WIDGET
    tabIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    tabIntent.putExtra(WidgetPrefs.PREF_WIDGET_TAB, Stuff.TYPE_ARTISTS)
    var tabIntentPending = PendingIntent.getBroadcast(
        context, Stuff.genHashCode(appWidgetId, 1), tabIntent,
        Stuff.updateCurrentOrImmutable
    )
    rv.setOnClickPendingIntent(R.id.appwidget_artists, tabIntentPending)

    tabIntent.putExtra(WidgetPrefs.PREF_WIDGET_TAB, Stuff.TYPE_ALBUMS)
    tabIntentPending = PendingIntent.getBroadcast(
        context, Stuff.genHashCode(appWidgetId, 2), tabIntent,
        Stuff.updateCurrentOrImmutable
    )
    rv.setOnClickPendingIntent(R.id.appwidget_albums, tabIntentPending)

    tabIntent.putExtra(WidgetPrefs.PREF_WIDGET_TAB, Stuff.TYPE_TRACKS)
    tabIntentPending = PendingIntent.getBroadcast(
        context, Stuff.genHashCode(appWidgetId, 3), tabIntent,
        Stuff.updateCurrentOrImmutable
    )
    rv.setOnClickPendingIntent(R.id.appwidget_tracks, tabIntentPending)

    rv.setInt(R.id.appwidget_bg, "setImageAlpha", (bgAlpha * 255).toInt())

    val tabShadowIds =
        arrayOf(R.id.appwidget_tracks_glow, R.id.appwidget_albums_glow, R.id.appwidget_artists_glow)
    val tabIndicatorShadowIds = arrayOf(
        R.id.appwidget_tracks_glow_shadow,
        R.id.appwidget_albums_glow_shadow,
        R.id.appwidget_artists_glow_shadow
    )
    val glowId = when (tab) {
        Stuff.TYPE_TRACKS -> R.id.appwidget_tracks_glow
        Stuff.TYPE_ALBUMS -> R.id.appwidget_albums_glow
        else -> R.id.appwidget_artists_glow
    }
    tabShadowIds.forEachIndexed { i, it ->
        val visibility = if (it == glowId)
            View.VISIBLE
        else
            View.INVISIBLE
        rv.setViewVisibility(it, visibility)
        rv.setViewVisibility(tabIndicatorShadowIds[i], visibility)
    }

    // Instruct the widget manager to update the widget
    appWidgetManager.updateAppWidget(appWidgetId, rv)
}