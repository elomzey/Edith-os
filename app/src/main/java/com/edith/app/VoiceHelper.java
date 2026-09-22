package com.edith.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

/** Voix native : SpeechRecognizer + TextToSpeech (WebSpeech est peu fiable dans un WebView). */
final class VoiceHelper {
    private final MainActivity act;
    private final TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

    VoiceHelper(MainActivity a) {
        act = a;
        tts = new TextToSpeech(a.getApplicationContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(Locale.FRANCE);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { act.emit("tts_start", "{}"); }
                    @Override public void onDone(String id) { act.emit("tts_done", "{}"); }
                    @Override public void onError(String id) { act.emit("tts_done", "{}"); }
                });
            }
        });
    }

    void speak(String text, String lang) {
        if (!ttsReady) throw new IllegalStateException("Synthèse vocale indisponible");
        if (lang != null && !lang.isEmpty()) tts.setLanguage(Locale.forLanguageTag(lang));
        String t = text.length() > 3900 ? text.substring(0, 3900) : text;
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "edith");
    }

    void stopSpeaking() {
        tts.stop();
    }

    void startListening(final String lang) {
        act.runOnUiThread(() -> {
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(act, new String[]{Manifest.permission.RECORD_AUDIO}, 12);
                act.emit("stt_error", "{\"code\":-1,\"message\":\"Autorise le micro puis réessaie\"}");
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(act)) {
                act.emit("stt_error", "{\"code\":-2,\"message\":\"Reconnaissance vocale indisponible sur cet appareil\"}");
                return;
            }
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(act);
                recognizer.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(i);
        });
    }

    void stopListening() {
        act.runOnUiThread(() -> {
            if (recognizer != null) recognizer.stopListening();
        });
    }

    void destroy() {
        tts.shutdown();
        if (recognizer != null) recognizer.destroy();
    }

    private void sendText(String type, Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        try {
            act.emit(type, new JSONObject().put("text", list.get(0)).toString());
        } catch (Exception ignored) {
            // rien à faire : le texte n'est simplement pas transmis
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { act.emit("stt_ready", "{}"); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onError(int error) { act.emit("stt_error", "{\"code\":" + error + "}"); }
        @Override public void onResults(Bundle results) { sendText("stt_final", results); }
        @Override public void onPartialResults(Bundle partial) { sendText("stt_partial", partial); }
        @Override public void onEvent(int eventType, Bundle params) {}
    };
}
