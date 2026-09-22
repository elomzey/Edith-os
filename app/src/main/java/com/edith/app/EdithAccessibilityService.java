package com.edith.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Touches, balayages et lecture d'écran sans root. À activer une fois à la main. */
public class EdithAccessibilityService extends AccessibilityService {

    static volatile EdithAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** Ne pas appeler depuis le thread principal (attend la fin du geste). */
    private boolean gesture(Path path, long durationMs) throws InterruptedException {
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs)));
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] done = {false};
        boolean sent = dispatchGesture(b.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                done[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription g) {
                latch.countDown();
            }
        }, null);
        if (!sent) return false;
        latch.await(5, TimeUnit.SECONDS);
        return done[0];
    }

    boolean tap(int x, int y) throws InterruptedException {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 50);
    }

    boolean swipe(int x1, int y1, int x2, int y2, int durationMs) throws InterruptedException {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, Math.min(Math.max(durationMs, 50), 3000));
    }

    boolean globalAction(String name) {
        switch (name) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: throw new IllegalArgumentException("Action système inconnue : " + name);
        }
    }

    /** Texte visible + boutons avec leurs coordonnées. Les champs de mot de passe sont ignorés. */
    String dumpScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        walk(root, 0, sb);
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || sb.length() > 8000 || depth > 25) return;
        if (n.isVisibleToUser() && !n.isPassword()) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String label = t != null && t.length() > 0 ? t.toString() : (d != null ? d.toString() : "");
            if (label.length() > 120) label = label.substring(0, 120);
            if (!label.isEmpty() || n.isClickable()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                sb.append("- ")
                        .append(n.isClickable() ? "[cliquable] " : "")
                        .append(label.isEmpty() ? "(sans texte)" : label)
                        .append(" @(").append(r.centerX()).append(",").append(r.centerY()).append(")\n");
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), depth + 1, sb);
        }
    }
}
