package com.edith.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Programme et annule les alarmes exactes qui déclenchent les tâches planifiées. */
final class TaskScheduler {
    private TaskScheduler() {}

    static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent pending(Context ctx, String recipeId) {
        Intent i = new Intent(ctx, TaskAlarmReceiver.class).putExtra("recipe_id", recipeId);
        return PendingIntent.getBroadcast(
                ctx, recipeId.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context ctx, String recipeId, long atMillis) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx, recipeId);
        if (canScheduleExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
        } else {
            // Repli tolérant : Android peut retarder le déclenchement de quelques minutes
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
        }
    }

    static void cancel(Context ctx, String recipeId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(ctx, recipeId));
    }
}
