package com.edith.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private WebView web;
    private VoiceHelper voice;
    private EdithBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.webview);
        web.setBackgroundColor(0xFF0A0A1A);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);          // les assets restent lisibles
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        voice = new VoiceHelper(this);
        bridge = new EdithBridge(this, web, voice);
        web.addJavascriptInterface(bridge, "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("file".equals(scheme)) return false;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                }
                return true; // la page EDITH ne navigue jamais ailleurs
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript("window.__edithBack ? window.__edithBack() : false", value -> {
                    if (!"true".equals(value)) moveTaskToBack(true);
                });
            }
        });

        SharedPreferences p = getSharedPreferences("edith_settings", MODE_PRIVATE);
        if (p.getBoolean("keep_alive", true)) setKeepAlive(true);

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    void setKeepAlive(boolean on) {
        Intent i = new Intent(this, BridgeService.class);
        if (on) {
            ContextCompat.startForegroundService(this, i);
        } else {
            stopService(i);
        }
    }

    /** Envoie un événement natif vers la page (voix, permissions…). */
    void emit(final String type, final String payload) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window.__edithEvent&&window.__edithEvent(" + JSONObject.quote(type) + ","
                        + JSONObject.quote(payload) + ")", null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < permissions.length; i++) {
                o.put(permissions[i], grantResults[i] == PackageManager.PERMISSION_GRANTED);
            }
        } catch (JSONException ignored) {
            // payload partiel : sans gravité
        }
        emit("permission", o.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bridge != null) bridge.shutdown();
        if (voice != null) voice.destroy();
        if (web != null) web.destroy();
    }
}
