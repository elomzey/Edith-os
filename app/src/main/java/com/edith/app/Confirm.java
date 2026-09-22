package com.edith.app;

import androidx.appcompat.app.AlertDialog;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Confirmation native : la page web (donc l'IA) ne peut pas la contourner. */
final class Confirm {
    private Confirm() {}

    static boolean ask(final MainActivity act, final String message) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] allowed = {false};
        final AlertDialog[] dialog = {null};

        act.runOnUiThread(() -> {
            try {
                dialog[0] = new AlertDialog.Builder(act)
                        .setTitle("EDITH demande ton autorisation")
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("Autoriser", (d, w) -> {
                            allowed[0] = true;
                            latch.countDown();
                        })
                        .setNegativeButton("Refuser", (d, w) -> latch.countDown())
                        .show();
            } catch (Exception e) {
                latch.countDown();
            }
        });

        try {
            boolean answered = latch.await(60, TimeUnit.SECONDS);
            if (!answered) {
                act.runOnUiThread(() -> {
                    if (dialog[0] != null) dialog[0].dismiss();
                });
            }
            return answered && allowed[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
