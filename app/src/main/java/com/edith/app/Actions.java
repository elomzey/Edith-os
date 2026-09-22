package com.edith.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SmsManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

/** Actions matérielles natives : aucune app tierce, aucun Termux:API. */
final class Actions {
    private final Context ctx;
    /** Nul en tâche de fond : pas de dialogue, pas de demande de permission à l'écran. */
    private final MainActivity act;

    Actions(Context ctx, MainActivity act) {
        this.ctx = ctx.getApplicationContext();
        this.act = act;
    }

    /** Utilisé par les tâches planifiées, qui tournent sans activité visible. */
    Actions(Context ctx) {
        this(ctx, null);
    }

    Actions(MainActivity act) {
        this(act, act);
    }

    static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    // ---------- Permissions ----------

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void need(final String perm) {
        if (has(perm)) return;
        if (act != null) {
            act.runOnUiThread(() -> ActivityCompat.requestPermissions(act, new String[]{perm}, 10));
            throw new IllegalStateException(
                    "Permission requise (une demande s'affiche). Accorde-la puis réessaie.");
        }
        throw new IllegalStateException("Permission manquante pour une tâche en arrière-plan : " + perm);
    }

    private static String permName(String key) {
        switch (key) {
            case "sms": return Manifest.permission.SEND_SMS;
            case "location": return Manifest.permission.ACCESS_FINE_LOCATION;
            case "mic": return Manifest.permission.RECORD_AUDIO;
            case "notifications":
                return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : null;
            default: return null;
        }
    }

    JSONObject permissions() throws Exception {
        JSONObject o = new JSONObject();
        for (String k : new String[]{"sms", "location", "mic", "notifications"}) {
            String p = permName(k);
            o.put(k, p == null || has(p));
        }
        return o;
    }

    JSONObject requestPermission(String key) throws Exception {
        final String p = permName(key);
        if (p == null) return ok();
        if (!has(p)) {
            if (act == null) throw new IllegalStateException("Indisponible en arrière-plan");
            act.runOnUiThread(() -> ActivityCompat.requestPermissions(act, new String[]{p}, 10));
        }
        return permissions();
    }

    // ---------- État de l'appareil ----------

    JSONObject battery() throws Exception {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) throw new IllegalStateException("Batterie illisible");
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        return new JSONObject()
                .put("percent", scale > 0 ? Math.round(level * 100f / scale) : -1)
                .put("charging", charging);
    }

    JSONObject wifi() throws Exception {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        return new JSONObject().put("enabled", wm != null && wm.isWifiEnabled());
    }

    /** Depuis Android 10, une app ne peut plus activer le Wi-Fi : on ouvre le panneau. */
    JSONObject wifiPanel() throws Exception {
        Intent i = Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                : new Intent(Settings.ACTION_WIFI_SETTINGS);
        start(i);
        return ok();
    }

    @SuppressLint("MissingPermission")
    JSONObject location() throws Exception {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)
                && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            need(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        for (String p : lm.getProviders(true)) {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
        }
        if (best == null) return new JSONObject().put("available", false);
        return new JSONObject()
                .put("available", true)
                .put("lat", best.getLatitude())
                .put("lon", best.getLongitude())
                .put("accuracy_m", Math.round(best.getAccuracy()))
                .put("age_s", (System.currentTimeMillis() - best.getTime()) / 1000);
    }

    // ---------- Contrôle direct ----------

    JSONObject torch(boolean on) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) {
                cm.setTorchMode(id, on);
                return new JSONObject().put("on", on);
            }
        }
        throw new IllegalStateException("Cet appareil n'a pas de flash");
    }

    /** level < 0 : lit seulement le volume média actuel. */
    JSONObject volume(int level) throws Exception {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (level >= 0) {
            int pct = Math.min(100, level);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
        }
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        return new JSONObject().put("percent", max > 0 ? Math.round(cur * 100f / max) : 0);
    }

    JSONObject sms(String number, String message) throws Exception {
        if (number.trim().isEmpty() || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Numéro et message requis");
        }
        if (message.length() > 1000) throw new IllegalArgumentException("Message trop long (1000 caractères max)");
        need(Manifest.permission.SEND_SMS);
        SmsManager sm = Build.VERSION.SDK_INT >= 31
                ? ctx.getSystemService(SmsManager.class)
                : SmsManager.getDefault();
        ArrayList<String> parts = sm.divideMessage(message);
        sm.sendMultipartTextMessage(number.trim(), null, parts, null, null);
        return ok();
    }

    JSONObject launchApp(String pkg, String name) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = null;
        if (pkg != null && !pkg.isEmpty()) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null && name != null && !name.isEmpty()) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            String q = name.toLowerCase(Locale.ROOT);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                if (label.contains(q)) {
                    launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                    break;
                }
            }
        }
        if (launch == null) throw new IllegalArgumentException("Application introuvable");
        start(launch);
        return ok();
    }

    JSONObject openUrl(String url) throws Exception {
        Uri u = Uri.parse(url);
        String s = u.getScheme();
        if (!"https".equals(s) && !"http".equals(s)) {
            throw new IllegalArgumentException("Seuls les liens http et https sont autorisés");
        }
        start(new Intent(Intent.ACTION_VIEW, u));
        return ok();
    }

    JSONObject openAccessibilitySettings() throws Exception {
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        return ok();
    }

    /** Écran « Infos de l'app » : le menu ⋮ y permet d'autoriser les paramètres restreints. */
    JSONObject openAppSettings() throws Exception {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.getPackageName())));
        return ok();
    }

    private void start(final Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (act != null) {
            act.runOnUiThread(() -> act.startActivity(i));
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ctx.startActivity(i));
        }
    }

    // ---------- Accessibilité (touches, balayages, lecture d'écran) ----------

    private EdithAccessibilityService svc() {
        EdithAccessibilityService s = EdithAccessibilityService.instance;
        if (s == null) {
            throw new IllegalStateException("Service d'accessibilité non activé (onglet Téléphone)");
        }
        return s;
    }

    JSONObject accessibilityStatus() throws Exception {
        return new JSONObject().put("enabled", EdithAccessibilityService.instance != null);
    }

    JSONObject tap(int x, int y) throws Exception {
        return new JSONObject().put("ok", svc().tap(x, y));
    }

    JSONObject swipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        return new JSONObject().put("ok", svc().swipe(x1, y1, x2, y2, durationMs));
    }

    JSONObject globalAction(String name) throws Exception {
        return new JSONObject().put("ok", svc().globalAction(name));
    }

    JSONObject readScreen() throws Exception {
        return new JSONObject().put("text", svc().dumpScreen());
    }
}
