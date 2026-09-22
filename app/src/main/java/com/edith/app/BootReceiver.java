package com.edith.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.core.content.ContextCompat;

/** Démarre le service au boot, seulement si l'utilisateur l'a activé (désactivé par défaut). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("edith_settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("start_on_boot", false)) return;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, BridgeService.class));
        } catch (Exception e) {
            Log.w("EDITH", "Démarrage au boot refusé par Android", e);
        }
    }
}
