package com.edith.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.Calendar;
import org.json.JSONObject;

/** Exécute une tâche planifiée en arrière-plan, sans écran ni confirmation à l'instant T :
 *  seules les actions déjà approuvées à la création de la tâche (voir Recipes "granted") s'exécutent. */
public class TaskExecutorService extends Service {
    private static final String CHANNEL_ID = "edith_tasks";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Tâches planifiées EDITH", NotificationManager.IMPORTANCE_LOW);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("EDITH exécute une tâche planifiée")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(2, n);
        }
        final String recipeId = intent != null ? intent.getStringExtra("recipe_id") : null;
        new Thread(() -> {
            try {
                runAndReschedule(recipeId);
            } catch (Exception e) {
                Log.e("EDITH", "Tâche planifiée en échec", e);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }, "edith-task").start();
        return START_NOT_STICKY;
    }

    private void runAndReschedule(String recipeId) throws Exception {
        if (recipeId == null) return;
        JSONObject recipe = Recipes.get(this, recipeId);
        if (recipe == null || !recipe.optBoolean("enabled", true)) return;

        RecipeExecutor.run(this, recipe);

        JSONObject schedule = recipe.optJSONObject("schedule");
        if (schedule != null && "daily".equals(schedule.optString("type"))) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, schedule.optInt("hour", 3));
            c.set(Calendar.MINUTE, schedule.optInt("minute", 0));
            c.set(Calendar.SECOND, 0);
            c.add(Calendar.DAY_OF_YEAR, 1);
            TaskScheduler.schedule(this, recipeId, c.getTimeInMillis());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
