package com.codyedwin.runelitemobile.launcher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import com.codyedwin.runelitemobile.R;

/** Home screen widget: one tap starts RuneLite with the saved launcher settings. */
public class PlayRuneLiteWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Intent intent = new Intent(context, PlayRuneLiteActivity.class);
        intent.setAction(PlayRuneLiteActivity.ACTION_PLAY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, flags);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_play_runelite);
            views.setOnClickPendingIntent(R.id.widget_play_root, pi);
            manager.updateAppWidget(id, views);
        }
    }
}
