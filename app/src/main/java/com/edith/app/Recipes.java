package com.edith.app;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tâches planifiées ("recettes") : un simple fichier JSON dans le dossier privé de l'app.
 * Pas de secret ici (les clés API restent dans SecureStore) : un fichier normal suffit.
 */
final class Recipes {
    private static final Object LOCK = new Object();

    private Recipes() {}

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "recipes.json");
    }

    static JSONArray list(Context ctx) throws Exception {
        synchronized (LOCK) {
            File f = file(ctx);
            if (!f.exists()) return new JSONArray();
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return s.trim().isEmpty() ? new JSONArray() : new JSONArray(s);
        }
    }

    private static void writeAll(Context ctx, JSONArray arr) throws Exception {
        File f = file(ctx);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        Files.write(tmp.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) throw new IllegalStateException("Écriture des tâches impossible");
    }

    static JSONObject get(Context ctx, String id) throws Exception {
        JSONArray arr = list(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.optString("id").equals(id)) return o;
        }
        return null;
    }

    static JSONObject save(Context ctx, JSONObject recipe) throws Exception {
        synchronized (LOCK) {
            JSONArray arr = list(ctx);
            String id = recipe.optString("id", "");
            if (id.isEmpty()) {
                id = "t" + System.currentTimeMillis();
                recipe.put("id", id);
            }
            JSONArray next = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optString("id").equals(id)) {
                    next.put(recipe);
                    replaced = true;
                } else {
                    next.put(o);
                }
            }
            if (!replaced) next.put(recipe);
            writeAll(ctx, next);
            return recipe;
        }
    }

    static void delete(Context ctx, String id) throws Exception {
        synchronized (LOCK) {
            JSONArray arr = list(ctx);
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("id").equals(id)) next.put(o);
            }
            writeAll(ctx, next);
        }
    }

    /** Ajoute le compte-rendu de la dernière exécution, appelé depuis le thread de la tâche. */
    static void recordRun(Context ctx, String id, String status, List<String> log) {
        try {
            synchronized (LOCK) {
                JSONObject r = get(ctx, id);
                if (r == null) return;
                JSONObject last = new JSONObject();
                last.put("ts", System.currentTimeMillis() / 1000);
                last.put("status", status);
                last.put("log", new JSONArray(log));
                r.put("last_run", last);
                save(ctx, r);
            }
        } catch (Exception ignored) {
            // Le compte-rendu est un confort : son échec ne doit pas casser la tâche
        }
    }
}
