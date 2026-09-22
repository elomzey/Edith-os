package com.edith.app;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/** Démarre Python (Chaquopy) une seule fois et appelle le module bridge.py. */
final class PythonHost {
    private static boolean ready = false;

    private PythonHost() {}

    static synchronized PyObject bridge(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(app));
        }
        PyObject mod = Python.getInstance().getModule("bridge");
        if (!ready) {
            mod.callAttr("init", app.getFilesDir().getAbsolutePath());
            ready = true;
        }
        return mod;
    }

    static String handle(Context ctx, String action, String argsJson) {
        return bridge(ctx).callAttr("handle", action, argsJson).toString();
    }
}
