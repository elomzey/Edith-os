package com.edith.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Niveau d'autorisation par action sensible : auto, confirm ou blocked.
 * Les actions non listées dans GATED sont toujours libres.
 */
final class Policy {
    static final String AUTO = "auto";
    static final String CONFIRM = "confirm";
    static final String BLOCKED = "blocked";
    static final String[] GATED = {"sms", "tap", "swipe", "global_action", "read_screen"};

    private final SharedPreferences prefs;

    Policy(Context c) {
        prefs = c.getSharedPreferences("edith_policy", Context.MODE_PRIVATE);
    }

    static boolean isGated(String action) {
        for (String g : GATED) {
            if (g.equals(action)) return true;
        }
        return false;
    }

    static boolean isLevel(String level) {
        return AUTO.equals(level) || CONFIRM.equals(level) || BLOCKED.equals(level);
    }

    /** 0 = libre, 1 = confirmation, 2 = bloqué. */
    static int rank(String level) {
        if (BLOCKED.equals(level)) return 2;
        if (CONFIRM.equals(level)) return 1;
        return 0;
    }

    String levelFor(String action) {
        if (!isGated(action)) return AUTO;
        return prefs.getString(action, CONFIRM);
    }

    void set(String action, String level) {
        prefs.edit().putString(action, level).apply();
    }

    JSONObject all() throws JSONException {
        JSONObject o = new JSONObject();
        for (String g : GATED) {
            o.put(g, levelFor(g));
        }
        return o;
    }
}
