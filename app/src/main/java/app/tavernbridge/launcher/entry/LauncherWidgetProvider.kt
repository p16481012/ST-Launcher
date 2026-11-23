package app.tavernbridge.launcher.entry

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.tavernbridge.launcher.R

class LauncherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.launcher_widget)
            listOf(
                R.id.widget_start to LauncherEntryAction.START,
                R.id.widget_stop to LauncherEntryAction.STOP,
                R.id.widget_open to LauncherEntryAction.OPEN,
            ).forEach { (viewId, action) ->
                views.setOnClickPendingIntent(viewId, LauncherEntryIntents.pendingIntent(context, action, "widget-$id"))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
