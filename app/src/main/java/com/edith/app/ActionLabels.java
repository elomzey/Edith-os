package com.edith.app;

import java.util.LinkedHashMap;
import java.util.Map;

/** Libellés en français des actions sensibles, utilisés dans les demandes de confirmation. */
final class ActionLabels {
    private ActionLabels() {}

    static final Map<String, String> FR = new LinkedHashMap<>();
    static {
        FR.put("sms", "envoyer des SMS");
        FR.put("tap", "toucher l\u2019écran");
        FR.put("swipe", "faire des balayages");
        FR.put("global_action", "utiliser retour / accueil / récents");
        FR.put("read_screen", "lire le contenu affiché à l\u2019écran");
    }

    static String label(String action) {
        String l = FR.get(action);
        return l != null ? l : action;
    }
}
