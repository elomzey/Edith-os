package com.edith.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pont unique entre la page (WebView) et le reste : actions Android natives et Python.
 * Aucun serveur local : rien n'est joignable par les autres apps du téléphone.
 * Chaque action sensible passe par Policy + Confirm avant d'être exécutée.
 */
public class EdithBridge {

    private static final String MASK = "\u2022\u2022\u2022\u2022";

    private static final Map<String, Object> DEFAULT_SETTINGS = new LinkedHashMap<>();

    static {
        DEFAULT_SETTINGS.put("keep_alive", true);
        DEFAULT_SETTINGS.put("start_on_boot", false);
        DEFAULT_SETTINGS.put("speak_replies", true);
        DEFAULT_SETTINGS.put("mask_cards", true);
        DEFAULT_SETTINGS.put("mask_emails", false);
        DEFAULT_SETTINGS.put("mask_phones", false);
        DEFAULT_SETTINGS.put("theme", "dark");
        DEFAULT_SETTINGS.put("lang", "fr-FR");
    }

    private final MainActivity act;
    private final WebView web;
    private final VoiceHelper voice;
    private final Actions actions;
    private final Policy policy;
    private final SharedPreferences settings;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private SecureStore secure;

    EdithBridge(MainActivity act, WebView web, VoiceHelper voice) {
        this.act = act;
        this.web = web;
        this.voice = voice;
        this.actions = new Actions(act);
        this.policy = new Policy(act);
        this.settings = act.getSharedPreferences("edith_settings", android.content.Context.MODE_PRIVATE);
    }

    void shutdown() {
        pool.shutdownNow();
    }

    /** Appelé par la page : le résultat revient via window.__edithResult. */
    @JavascriptInterface
    public void call(final int id, final String action, final String argsJson) {
        pool.execute(() -> {
            try {
                JSONObject args = (argsJson == null || argsJson.isEmpty())
                        ? new JSONObject() : new JSONObject(argsJson);
                reply(id, true, dispatch(action, args));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                reply(id, false, msg);
            }
        });
    }

    private void reply(int id, boolean ok, String payload) {
        final String js = "window.__edithResult&&window.__edithResult(" + id + "," + ok + ","
                + JSONObject.quote(payload) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private String dispatch(String action, JSONObject args) throws Exception {
        // 1. Garde-fou : niveau d'autorisation de l'action
        String level = policy.levelFor(action);
        if (Policy.BLOCKED.equals(level)) {
            throw new SecurityException("Action bloquée dans les réglages : " + action);
        }
        if (Policy.CONFIRM.equals(level) && !Confirm.ask(act, describe(action, args))) {
            throw new SecurityException("Action refusée : " + action);
        }

        // 2. Exécution
        switch (action) {
            case "battery": return actions.battery().toString();
            case "wifi": return actions.wifi().toString();
            case "wifi_panel": return actions.wifiPanel().toString();
            case "location": return actions.location().toString();
            case "torch": return actions.torch(args.optBoolean("on", true)).toString();
            case "volume": return actions.volume(args.optInt("level", -1)).toString();
            case "sms": return actions.sms(args.optString("number"), args.optString("message")).toString();
            case "launch_app":
                return actions.launchApp(args.optString("package"), args.optString("name")).toString();
            case "open_url": return actions.openUrl(args.optString("url")).toString();
            case "permissions": return actions.permissions().toString();
            case "request_permission": return actions.requestPermission(args.optString("name")).toString();

            case "accessibility_status": return actions.accessibilityStatus().toString();
            case "open_accessibility_settings": return actions.openAccessibilitySettings().toString();
            case "open_app_settings": return actions.openAppSettings().toString();
            case "tap": return actions.tap(args.getInt("x"), args.getInt("y")).toString();
            case "swipe":
                return actions.swipe(args.getInt("x1"), args.getInt("y1"), args.getInt("x2"),
                        args.getInt("y2"), args.optInt("duration", 400)).toString();
            case "global_action": return actions.globalAction(args.optString("name")).toString();
            case "read_screen": return actions.readScreen().toString();

            case "tts_speak":
                voice.speak(args.optString("text"), args.optString("lang"));
                return Actions.ok().toString();
            case "tts_stop":
                voice.stopSpeaking();
                return Actions.ok().toString();
            case "stt_start":
                voice.startListening(args.optString("lang", "fr-FR"));
                return Actions.ok().toString();
            case "stt_stop":
                voice.stopListening();
                return Actions.ok().toString();

            case "list_tasks": return Recipes.list(act).toString();
            case "save_task": return saveTask(args).toString();
            case "delete_task":
                TaskScheduler.cancel(act, args.getString("id"));
                Recipes.delete(act, args.getString("id"));
                return Actions.ok().toString();
            case "exact_alarm_status":
                return new JSONObject().put("granted", TaskScheduler.canScheduleExact(act)).toString();
            case "open_exact_alarm_settings": return openExactAlarmSettings().toString();

            case "get_settings": return readSettings().toString();
            case "set_setting": return setSetting(args).toString();
            case "get_policies": return policy.all().toString();
            case "set_policy": return setPolicy(args).toString();
            case "get_providers":
                return new JSONObject().put("providers", maskedProviders()).toString();
            case "save_providers": return saveProviders(args.getJSONArray("providers")).toString();

            case "chat":
            case "list_models":
            case "agents_autoconfigure":
            case "agent_chat":
                // La clé API ne quitte jamais Java sauf pour cet appel ; Python ne la stocke pas.
                args.put("_providers", providersFull());
                args.put("_mask", maskOptions());
                return PythonHost.handle(act, action, args.toString());
            case "agents_get":
            case "agents_save":
            case "ping":
            case "usage":
            case "memory_get":
            case "memory_clear":
                return PythonHost.handle(act, action, args.toString());

            default:
                throw new IllegalArgumentException("Action inconnue : " + action);
        }
    }

    private String describe(String action, JSONObject args) {
        switch (action) {
            case "sms":
                return "Envoyer un SMS à " + args.optString("number") + " :\n« " + args.optString("message") + " »";
            case "tap":
                return "Toucher l'écran en (" + args.optInt("x") + ", " + args.optInt("y") + ")";
            case "swipe":
                return "Faire glisser le doigt sur l'écran";
            case "global_action":
                return "Action système : " + args.optString("name");
            case "read_screen":
                return "Lire le contenu affiché à l'écran";
            default:
                return action;
        }
    }

    // ---------- Réglages libres ----------

    private JSONObject readSettings() throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : DEFAULT_SETTINGS.entrySet()) {
            if (e.getValue() instanceof Boolean) {
                o.put(e.getKey(), settings.getBoolean(e.getKey(), (Boolean) e.getValue()));
            } else {
                o.put(e.getKey(), settings.getString(e.getKey(), (String) e.getValue()));
            }
        }
        return o;
    }

    private JSONObject setSetting(JSONObject args) throws Exception {
        String key = args.getString("key");
        Object def = DEFAULT_SETTINGS.get(key);
        if (def == null) throw new IllegalArgumentException("Réglage inconnu : " + key);
        SharedPreferences.Editor ed = settings.edit();
        if (def instanceof Boolean) {
            ed.putBoolean(key, args.getBoolean("value"));
        } else {
            ed.putString(key, args.getString("value"));
        }
        ed.apply();
        if ("keep_alive".equals(key)) act.setKeepAlive(args.getBoolean("value"));
        return readSettings();
    }

    // ---------- Réglages sensibles : jamais assouplis sans confirmation native ----------

    private JSONObject setPolicy(JSONObject args) throws Exception {
        String action = args.getString("action");
        String level = args.getString("level");
        if (!Policy.isGated(action) || !Policy.isLevel(level)) {
            throw new IllegalArgumentException("Réglage invalide");
        }
        String current = policy.levelFor(action);
        if (Policy.rank(level) < Policy.rank(current)) {
            String q = "Autoriser EDITH à exécuter « " + action + " » avec moins de vérifications ?\n"
                    + current + " → " + level;
            if (!Confirm.ask(act, q)) throw new SecurityException("Changement refusé");
        }
        policy.set(action, level);
        return policy.all();
    }

    // ---------- Fournisseurs d'API (clés chiffrées) ----------

    private synchronized SecureStore secure() {
        if (secure == null) secure = new SecureStore(act);
        return secure;
    }

    private String providersFull() {
        return secure().get("providers", "[]");
    }

    private JSONArray maskedProviders() throws Exception {
        JSONArray arr = new JSONArray(providersFull());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            String k = p.optString("api_key", "");
            p.put("api_key", k.isEmpty() ? "" : MASK + (k.length() > 4 ? k.substring(k.length() - 4) : ""));
        }
        return arr;
    }

    private JSONObject saveProviders(JSONArray incoming) throws Exception {
        JSONArray old = new JSONArray(providersFull());
        Map<String, String> keys = new HashMap<>();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.getJSONObject(i);
            keys.put(o.optString("id"), o.optString("api_key", ""));
        }
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject p = incoming.getJSONObject(i);
            if (p.optString("api_key", "").startsWith(MASK)) {
                String previous = keys.get(p.optString("id"));
                p.put("api_key", previous != null ? previous : "");
            }
        }
        secure().put("providers", incoming.toString());
        return Actions.ok();
    }

    // ---------- Tâches planifiées ----------

    /**
     * Une tâche approuvée peut, à l'heure dite, exécuter sans redemander les actions
     * qu'elle utilise déjà — c'est pourquoi l'autorisation est demandée une seule fois,
     * ici, à l'enregistrement, et non à chaque exécution nocturne.
     */
    private JSONObject saveTask(JSONObject args) throws Exception {
        String title = args.optString("title", "").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("Donne un nom à la tâche");
        JSONArray steps = args.optJSONArray("steps");
        if (steps == null || steps.length() == 0) throw new IllegalArgumentException("Ajoute au moins une étape");
        if (steps.length() > 20) throw new IllegalArgumentException("20 étapes maximum par tâche");
        JSONObject schedule = args.optJSONObject("schedule");
        if (schedule == null) throw new IllegalArgumentException("Choisis une fréquence");

        boolean enabled = args.optBoolean("enabled", true);
        if (enabled) {
            long at = nextTrigger(schedule);
            if ("once".equals(schedule.optString("type", "once")) && at <= System.currentTimeMillis() + 30000) {
                throw new IllegalArgumentException("Choisis une date et une heure dans le futur");
            }
        }

        String id = args.optString("id", "");
        JSONObject existing = id.isEmpty() ? null : Recipes.get(act, id);
        Set<String> already = new HashSet<>();
        if (existing != null) {
            JSONArray g = existing.optJSONArray("granted");
            if (g != null) for (int i = 0; i < g.length(); i++) already.add(g.optString(i));
        }

        Set<String> needed = new LinkedHashSet<>();
        for (int i = 0; i < steps.length(); i++) {
            String a = steps.getJSONObject(i).optString("action");
            if (Policy.isGated(a)) needed.add(a);
        }
        List<String> toApprove = new ArrayList<>();
        for (String a : needed) if (!already.contains(a)) toApprove.add(a);

        if (!toApprove.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "Une fois activée, cette tâche pourra, sans te redemander à chaque fois :\n");
            for (String a : toApprove) msg.append("• ").append(ActionLabels.label(a)).append("\n");
            msg.append("\nElle peut s'exécuter seule, y compris pendant que tu ne regardes pas le téléphone.");
            if (!Confirm.ask(act, msg.toString())) {
                throw new SecurityException("Tâche non enregistrée : autorisation refusée");
            }
        }
        already.addAll(needed);

        JSONObject recipe = new JSONObject();
        if (!id.isEmpty()) recipe.put("id", id);
        recipe.put("title", title);
        recipe.put("enabled", enabled);
        recipe.put("schedule", schedule);
        recipe.put("steps", steps);
        recipe.put("granted", new JSONArray(already));
        if (existing != null && existing.has("last_run")) recipe.put("last_run", existing.get("last_run"));

        JSONObject saved = Recipes.save(act, recipe);
        String rid = saved.getString("id");
        TaskScheduler.cancel(act, rid);
        if (enabled) TaskScheduler.schedule(act, rid, nextTrigger(schedule));
        return saved;
    }

    private long nextTrigger(JSONObject schedule) {
        Calendar c = Calendar.getInstance();
        int hour = schedule.optInt("hour", 3);
        int minute = schedule.optInt("minute", 0);
        if ("once".equals(schedule.optString("type", "once")) && schedule.has("date")) {
            String[] parts = schedule.optString("date").split("-");
            c.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]), hour, minute, 0);
        } else {
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
            c.set(Calendar.SECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private JSONObject openExactAlarmSettings() throws Exception {
        if (Build.VERSION.SDK_INT >= 31) {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.runOnUiThread(() -> act.startActivity(i));
        }
        return Actions.ok();
    }

    private JSONObject maskOptions() throws Exception {
        return new JSONObject()
                .put("cards", settings.getBoolean("mask_cards", true))
                .put("emails", settings.getBoolean("mask_emails", false))
                .put("phones", settings.getBoolean("mask_phones", false));
    }
}
