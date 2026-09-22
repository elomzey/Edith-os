package com.edith.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

/** Réveillé par AlarmManager à l'heure prévue ; démarre le service qui exécute la tâche. */
public class TaskAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("recipe_id");
        if (id == null) return;
        Intent svc = new Intent(context, TaskExecutorService.class).putExtra("recipe_id", id);
        try {
            ContextCompat.startForegroundService(context, svc);
        } catch (Exception e) {
            Log.w("EDITH", "Démarrage du service de tâche refusé par Android", e);
        }
    }
}
