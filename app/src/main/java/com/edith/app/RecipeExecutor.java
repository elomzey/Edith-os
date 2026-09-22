package com.edith.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Rejoue les étapes d'une tâche planifiée. Seules les actions listées dans "granted"
 * (approuvées une fois par l'utilisateur à la création ou à la modification de la tâche)
 * sont exécutées ; toute autre action sensible est refusée automatiquement.
 */
final class RecipeExecutor {
    private RecipeExecutor() {}

    static void run(Context ctx, JSONObject recipe) {
        Actions actions = new Actions(ctx);
        Set<String> granted = new HashSet<>();
        JSONArray g = recipe.optJSONArray("granted");
        if (g != null) for (int i = 0; i < g.length(); i++) granted.add(g.optString(i));

        JSONArray steps = recipe.optJSONArray("steps");
        List<String> log = new ArrayList<>();
        String status = "ok";

        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String action = step.optString("action");
            JSONObject args = step.optJSONObject("args");
            if (args == null) args = new JSONObject();
            try {
                if (Policy.isGated(action) && !granted.contains(action)) {
                    throw new SecurityException("action non autorisée pour cette tâche");
                }
                execute(actions, action, args);
                JSONObject verify = step.optJSONObject("verify");
                String contains = verify != null ? verify.optString("contains", "") : "";
                if (!contains.isEmpty()) {
                    String screen = actions.readScreen().optString("text", "");
                    if (!screen.contains(contains)) {
                        throw new IllegalStateException("vérification échouée (texte absent de l'écran)");
                    }
                }
                log.add("OK   " + action);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.add("ECH  " + action + " : " + msg);
                if (!"continue".equals(step.optString("on_fail", "stop"))) {
                    status = "failed";
                    break;
                }
            }
            try {
                Thread.sleep(300); // laisse l'écran/l'app suivante s'installer avant la suite
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                status = "failed";
                break;
            }
        }
        Recipes.recordRun(ctx, recipe.optString("id"), status, log);
    }

    private static void execute(Actions a, String action, JSONObject args) throws Exception {
        switch (action) {
            case "battery": a.battery(); return;
            case "wifi": a.wifi(); return;
            case "location": a.location(); return;
            case "torch": a.torch(args.optBoolean("on", true)); return;
            case "volume": a.volume(args.optInt("level", -1)); return;
            case "sms": a.sms(args.optString("number"), args.optString("message")); return;
            case "launch_app": a.launchApp(args.optString("package"), args.optString("name")); return;
            case "open_url": a.openUrl(args.optString("url")); return;
            case "tap": a.tap(args.optInt("x"), args.optInt("y")); return;
            case "swipe":
                a.swipe(args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"),
                        args.optInt("duration", 400));
                return;
            case "global_action": a.globalAction(args.optString("name")); return;
            case "read_screen": a.readScreen(); return;
            default: throw new IllegalArgumentException("action inconnue : " + action);
        }
    }
}
