package com.teinzz.aizoomlistener;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int RECORD_AUDIO_REQUEST = 701;
    private static final long SEGMENT_MS = 12000L;
    private static final int BG = Color.rgb(7, 10, 19);
    private static final int CARD = Color.rgb(19, 27, 43);
    private static final int TEXT = Color.rgb(242, 246, 255);
    private static final int MUTED = Color.rgb(153, 169, 194);
    private static final int ACCENT = Color.rgb(104, 139, 255);

    private EditText backendInput;
    private EditText topicInput;
    private TextView statusView;
    private TextView transcriptView;
    private TextView answerView;
    private Button startButton;
    private Button stopButton;
    private volatile boolean listening;
    private boolean receiverRegistered;
    private volatile String configuredBackendUrl = "";

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String type = intent.getStringExtra("type");
            String value = intent.getStringExtra("text");
            if ("status".equals(type)) {
                setStatus(value == null ? "" : value);
            } else if ("transcript".equals(type)) {
                appendOutput(transcriptView, value == null ? "" : value, true);
            } else if ("answer".equals(type)) {
                appendOutput(answerView, value == null ? "" : value, false);
            } else if ("listening".equals(type)) {
                listening = intent.getBooleanExtra("value", false);
                updateButtons();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(0);
        buildUi();
        SharedPreferences prefs = getSharedPreferences("listener_prefs", MODE_PRIVATE);
        configuredBackendUrl = prefs.getString("backend_url", "https://ai-zoom-listener.vercel.app");
        backendInput.setText(configuredBackendUrl);
        topicInput.setText(prefs.getString("meeting_topic", ""));
        updateButtons();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(28));
        page.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("AI-Zoom Listener", 29, TEXT, true);
        page.addView(title);
        TextView subtitle = text("Dengarkan lewat mikrofon, transkripsikan, lalu dapatkan saran AI.", 14, MUTED, false);
        subtitle.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams subParams = params(-1, -2);
        subParams.topMargin = dp(8);
        subParams.bottomMargin = dp(5);
        page.addView(subtitle, subParams);

        LinearLayout configCard = card(page);
        addLabel(configCard, "BACKEND URL");
        backendInput = edit("https://nama-proyek.vercel.app", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        configCard.addView(backendInput, params(-1, dp(54)));
        addLabel(configCard, "TOPIK / KONTEKS (OPSIONAL)");
        topicInput = edit("Contoh: materi biologi, proyek, atau wawancara", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        configCard.addView(topicInput, params(-1, dp(62)));
        Button saveButton = button("Simpan konfigurasi", ACCENT, BG);
        LinearLayout.LayoutParams saveParams = params(-1, dp(49));
        saveParams.topMargin = dp(15);
        configCard.addView(saveButton, saveParams);
        saveButton.setOnClickListener(v -> saveConfiguration());

        LinearLayout controlCard = card(page);
        controlCard.setBackground(roundedGradient(Color.rgb(35, 49, 79), Color.rgb(18, 26, 43), Color.rgb(80, 105, 161), 25));
        statusView = text("Belum mulai", 13, MUTED, true);
        statusView.setPadding(dp(13), dp(10), dp(13), dp(10));
        statusView.setBackground(roundedGradient(Color.rgb(27, 40, 62), Color.rgb(18, 27, 43),
                Color.rgb(57, 79, 119), 18));
        controlCard.addView(statusView);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRow = params(-1, dp(51));
        buttonRow.topMargin = dp(12);
        controlCard.addView(buttons, buttonRow);
        startButton = button("Mulai dengarkan", ACCENT, BG);
        stopButton = button("Berhenti", Color.rgb(39, 49, 68), TEXT);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1f);
        half.rightMargin = dp(5);
        buttons.addView(startButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -1, 1f);
        half2.leftMargin = dp(5);
        buttons.addView(stopButton, half2);
        startButton.setOnClickListener(v -> startListening());
        stopButton.setOnClickListener(v -> stopListening());

        addOutputCard(page, "Transkrip", true);
        addOutputCard(page, "Saran AI", false);

        TextView privacy = text("Audio diproses melalui backend yang kamu konfigurasi. Gunakan rekaman hanya dengan izin yang sesuai. Aplikasi ini memakai mikrofon; audio internal Zoom belum tentu tertangkap.", 12, MUTED, false);
        privacy.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams privacyParams = params(-1, -2);
        privacyParams.topMargin = dp(18);
        page.addView(privacy, privacyParams);

        setContentView(scroll);
    }

    private void addOutputCard(LinearLayout page, String heading, boolean isTranscript) {
        LinearLayout outputCard = card(page);
        TextView label = text(heading, 18, TEXT, true);
        outputCard.addView(label);
        TextView output = text(isTranscript ? "Transkrip akan muncul di sini." : "Saran jawaban akan muncul di sini.", 14, isTranscript ? MUTED : TEXT, false);
        output.setTextIsSelectable(true);
        output.setMinHeight(dp(48));
        output.setLineSpacing(dp(2), 1.0f);
        output.setPadding(0, dp(10), 0, dp(2));
        outputCard.addView(output, params(-1, -2));
        if (isTranscript) transcriptView = output;
        else answerView = output;

        if (!isTranscript) {
            Button clear = button("Bersihkan percakapan", Color.rgb(39, 49, 68), TEXT);
            LinearLayout.LayoutParams p = params(-1, dp(44));
            p.topMargin = dp(12);
            outputCard.addView(clear, p);
            clear.setOnClickListener(v -> {
                Intent clearIntent = new Intent(this, AudioListenerService.class);
                clearIntent.setAction(AudioListenerService.ACTION_CLEAR);
                startService(clearIntent);
                getSharedPreferences("listener_prefs", MODE_PRIVATE).edit()
                        .remove("transcript_history").remove("last_answer").apply();
                transcriptView.setText("Transkrip akan muncul di sini.");
                answerView.setText("Saran jawaban akan muncul di sini.");
                setStatus(listening ? "Riwayat percakapan dibersihkan; perekaman tetap aktif." : "Percakapan dibersihkan.");
            });
        }
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(roundedGradient(Color.rgb(28, 38, 59), CARD,
                Color.rgb(55, 72, 105), 25));
        box.setElevation(dp(5));
        box.setClipToOutline(true);
        LinearLayout.LayoutParams p = params(-1, -2);
        p.topMargin = dp(13);
        parent.addView(box, p);
        return box;
    }

    private void addLabel(LinearLayout parent, String value) {
        TextView label = text(value, 11, MUTED, true);
        label.setLetterSpacing(0.07f);
        LinearLayout.LayoutParams p = params(-1, -2);
        p.topMargin = dp(3);
        p.bottomMargin = dp(8);
        parent.addView(label, p);
    }

    private EditText edit(String hint, int inputType) {
        EditText field = new EditText(this);
        boolean multiLine = (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        field.setSingleLine(!multiLine);
        if (multiLine) field.setMaxLines(2);
        field.setTextSize(14);
        field.setTextColor(TEXT);
        field.setHintTextColor(Color.rgb(133, 149, 176));
        field.setHint(hint);
        field.setInputType(inputType);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setPadding(dp(15), dp(7), dp(15), dp(7));
        field.setBackground(roundedGradient(Color.rgb(35, 47, 70), Color.rgb(20, 29, 46),
                Color.rgb(63, 82, 119), 18));
        return field;
    }

    private Button button(String value, int background, int foreground) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(foreground);
        b.setLetterSpacing(0.01f);
        boolean primary = background == ACCENT;
        int top = primary ? Color.rgb(157, 181, 255) : lighter(background, 1.18f);
        int stroke = primary ? Color.rgb(184, 201, 255) : Color.rgb(79, 93, 122);
        b.setBackground(roundedGradient(top, background, stroke, 18));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setElevation(dp(primary ? 5 : 2));
        return b;
    }

    private GradientDrawable roundedGradient(int top, int bottom, int stroke, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int lighter(int color, float amount) {
        return Color.rgb(
                Math.min(255, Math.round(Color.red(color) * amount)),
                Math.min(255, Math.round(Color.green(color) * amount)),
                Math.min(255, Math.round(Color.blue(color) * amount)));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveConfiguration() {
        String url = backendInput.getText().toString().trim();
        String topic = topicInput.getText().toString().trim();
        configuredBackendUrl = url;
        getSharedPreferences("listener_prefs", MODE_PRIVATE).edit()
                .putString("backend_url", url)
                .putString("meeting_topic", topic)
                .apply();
        setStatus(url.startsWith("https://") ? "Konfigurasi disimpan." : "Masukkan URL backend HTTPS dari deployment Vercel.");
    }

    private String baseUrl() {
        String value = configuredBackendUrl == null ? "" : configuredBackendUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private void startListening() {
        saveConfiguration();
        if (!baseUrl().startsWith("https://")) {
            setStatus("Isi URL backend Vercel yang sudah aktif terlebih dahulu.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST);
            return;
        }
        if (listening) return;

        Intent serviceIntent = new Intent(this, AudioListenerService.class);
        serviceIntent.setAction(AudioListenerService.ACTION_START);
        serviceIntent.putExtra("backend_url", baseUrl());
        serviceIntent.putExtra("meeting_topic", topicInput.getText().toString().trim());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            listening = true;
            updateButtons();
            setStatus("Menyiapkan layanan mikrofon...");
        } catch (Exception e) {
            listening = false;
            updateButtons();
            setStatus("Gagal memulai layanan mikrofon: " + safeMessage(e));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                setStatus("Izin mikrofon diperlukan untuk mulai merekam.");
            }
        }
    }

    private void stopListening() {
        Intent intent = new Intent(this, AudioListenerService.class);
        intent.setAction(AudioListenerService.ACTION_STOP);
        try {
            startService(intent);
            setStatus("Menghentikan mikrofon dan menyelesaikan audio yang tersisa...");
        } catch (Exception e) {
            setStatus("Gagal menghentikan layanan: " + safeMessage(e));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AudioListenerService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }
        receiverRegistered = true;

        SharedPreferences prefs = getSharedPreferences("listener_prefs", MODE_PRIVATE);
        configuredBackendUrl = prefs.getString("backend_url", configuredBackendUrl);
        String history = prefs.getString("transcript_history", "");
        String lastAnswer = prefs.getString("last_answer", "");
        String status = prefs.getString("status", "");
        if (history.isEmpty()) {
            transcriptView.setText("Transkrip akan muncul di sini.");
        } else {
            transcriptView.setText(history);
        }
        if (lastAnswer.isEmpty()) {
            answerView.setText("Saran jawaban akan muncul di sini.");
        } else {
            answerView.setText(lastAnswer);
        }
        listening = prefs.getBoolean("is_listening", false);
        if (statusView != null && !status.isEmpty()) statusView.setText(status);
        updateButtons();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(serviceReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void appendOutput(TextView target, String value, boolean transcript) {
        runOnUiThread(() -> {
            String old = target.getText().toString();
            if (old.startsWith("Transkrip akan muncul") || old.startsWith("Saran jawaban akan muncul")) old = "";
            String updated = old.isEmpty() ? value : old + (transcript ? "\n\n" : "\n\n────────\n\n") + value;
            int maximum = transcript ? 14000 : 10000;
            if (updated.length() > maximum) updated = updated.substring(updated.length() - maximum);
            target.setText(updated);
        });
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 260 ? message.substring(0, 260) : message;
    }

    private void setStatus(String message) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(message);
        });
    }

    private void updateButtons() {
        if (startButton == null || stopButton == null) return;
        startButton.setEnabled(!listening);
        stopButton.setEnabled(listening);
        startButton.setAlpha(listening ? 0.5f : 1f);
        stopButton.setAlpha(listening ? 1f : 0.5f);
    }

    @Override
    protected void onDestroy() {
        // Recording is owned by AudioListenerService and may continue while Zoom is foreground.
        super.onDestroy();
    }
}
