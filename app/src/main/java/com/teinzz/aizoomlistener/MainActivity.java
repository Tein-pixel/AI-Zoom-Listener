package com.teinzz.aizoomlistener;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private static final int BG = Color.rgb(12, 16, 24);
    private static final int CARD = Color.rgb(22, 29, 41);
    private static final int TEXT = Color.rgb(238, 243, 251);
    private static final int MUTED = Color.rgb(155, 169, 190);
    private static final int ACCENT = Color.rgb(114, 148, 255);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkQueue = Executors.newSingleThreadExecutor();
    private final Object transcriptLock = new Object();
    private final StringBuilder transcriptHistory = new StringBuilder();

    private EditText backendInput;
    private EditText topicInput;
    private TextView statusView;
    private TextView transcriptView;
    private TextView answerView;
    private Button startButton;
    private Button stopButton;
    private MediaRecorder recorder;
    private File currentAudioFile;
    private long segmentStartedAt;
    private volatile boolean listening;
    private volatile String configuredBackendUrl = "";

    private final Runnable rotateSegment = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            File completed = stopCurrentSegment();
            if (listening) startSegment();
            if (completed != null) enqueueAudio(completed);
            if (listening) handler.postDelayed(this, SEGMENT_MS);
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
        configuredBackendUrl = prefs.getString("backend_url", "");
        backendInput.setText(configuredBackendUrl);
        topicInput.setText(prefs.getString("meeting_topic", ""));
        updateButtons();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(24), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("AI-Zoom Listener", 27, TEXT, true);
        page.addView(title);
        TextView subtitle = text("Dengarkan lewat mikrofon, transkripkan, lalu dapatkan saran AI.", 14, MUTED, false);
        LinearLayout.LayoutParams subParams = params(-1, -2);
        subParams.topMargin = dp(6);
        page.addView(subtitle, subParams);

        LinearLayout configCard = card(page);
        addLabel(configCard, "URL backend Vercel");
        backendInput = edit("https://nama-proyek.vercel.app", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        configCard.addView(backendInput, params(-1, dp(50)));
        addLabel(configCard, "Topik rapat (opsional)");
        topicInput = edit("Contoh: materi biologi, proyek, atau wawancara", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        configCard.addView(topicInput, params(-1, dp(50)));
        Button saveButton = button("Simpan konfigurasi", ACCENT, BG);
        LinearLayout.LayoutParams saveParams = params(-1, dp(46));
        saveParams.topMargin = dp(12);
        configCard.addView(saveButton, saveParams);
        saveButton.setOnClickListener(v -> saveConfiguration());

        LinearLayout controlCard = card(page);
        statusView = text("Belum mulai", 14, MUTED, false);
        controlCard.addView(statusView);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRow = params(-1, dp(50));
        buttonRow.topMargin = dp(14);
        controlCard.addView(buttons, buttonRow);
        startButton = button("Mulai dengarkan", ACCENT, BG);
        stopButton = button("Berhenti", Color.rgb(50, 62, 79), TEXT);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1f);
        half.rightMargin = dp(6);
        buttons.addView(startButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -1, 1f);
        half2.leftMargin = dp(6);
        buttons.addView(stopButton, half2);
        startButton.setOnClickListener(v -> startListening());
        stopButton.setOnClickListener(v -> stopListening());

        addOutputCard(page, "Transkrip", true);
        addOutputCard(page, "Saran AI", false);

        TextView privacy = text("Audio diproses melalui backend yang kamu konfigurasi. Gunakan rekaman hanya dengan izin yang sesuai. Aplikasi ini memakai mikrofon; audio internal Zoom belum tentu tertangkap.", 12, MUTED, false);
        LinearLayout.LayoutParams privacyParams = params(-1, -2);
        privacyParams.topMargin = dp(15);
        page.addView(privacy, privacyParams);

        setContentView(scroll);
    }

    private void addOutputCard(LinearLayout page, String heading, boolean isTranscript) {
        LinearLayout outputCard = card(page);
        TextView label = text(heading, 17, TEXT, true);
        outputCard.addView(label);
        TextView output = text(isTranscript ? "Transkrip akan muncul di sini." : "Saran jawaban akan muncul di sini.", 14, isTranscript ? MUTED : TEXT, false);
        output.setTextIsSelectable(true);
        output.setMinHeight(dp(80));
        output.setPadding(0, dp(12), 0, dp(2));
        outputCard.addView(output, params(-1, -2));
        if (isTranscript) transcriptView = output;
        else answerView = output;

        if (!isTranscript) {
            Button clear = button("Bersihkan percakapan", Color.rgb(50, 62, 79), TEXT);
            LinearLayout.LayoutParams p = params(-1, dp(44));
            p.topMargin = dp(12);
            outputCard.addView(clear, p);
            clear.setOnClickListener(v -> {
                synchronized (transcriptLock) { transcriptHistory.setLength(0); }
                transcriptView.setText("Transkrip akan muncul di sini.");
                answerView.setText("Saran jawaban akan muncul di sini.");
                setStatus(listening ? "Sedang mendengarkan..." : "Percakapan dibersihkan.");
            });
        }
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(15), dp(15), dp(15));
        box.setBackgroundColor(CARD);
        LinearLayout.LayoutParams p = params(-1, -2);
        p.topMargin = dp(16);
        parent.addView(box, p);
        return box;
    }

    private void addLabel(LinearLayout parent, String value) {
        TextView label = text(value, 13, MUTED, true);
        LinearLayout.LayoutParams p = params(-1, -2);
        p.topMargin = dp(8);
        p.bottomMargin = dp(7);
        parent.addView(label, p);
    }

    private EditText edit(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setPadding(dp(11), 0, dp(11), 0);
        field.setBackgroundColor(Color.rgb(33, 42, 57));
        return field;
    }

    private Button button(String value, int background, int foreground) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(foreground);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        return b;
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
        if (!baseUrl().startsWith("https://") || baseUrl().contains("nama-proyek.vercel.app")) {
            setStatus("Isi URL backend Vercel yang sudah aktif terlebih dahulu.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST);
            return;
        }
        if (listening) return;
        listening = true;
        startSegment();
        if (listening) {
            handler.removeCallbacks(rotateSegment);
            handler.postDelayed(rotateSegment, SEGMENT_MS);
            setStatus("Sedang mendengarkan melalui mikrofon...");
        }
        updateButtons();
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

    private void startSegment() {
        try {
            currentAudioFile = File.createTempFile("zoom-audio-", ".m4a", getCacheDir());
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(currentAudioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            segmentStartedAt = System.currentTimeMillis();
        } catch (Exception e) {
            listening = false;
            releaseRecorder();
            setStatus("Gagal memulai mikrofon: " + safeMessage(e));
            updateButtons();
        }
    }

    private File stopCurrentSegment() {
        File finished = currentAudioFile;
        currentAudioFile = null;
        MediaRecorder activeRecorder = recorder;
        recorder = null;
        if (activeRecorder == null) return null;
        try {
            long elapsed = System.currentTimeMillis() - segmentStartedAt;
            if (elapsed < 800) {
                activeRecorder.reset();
                if (finished != null) finished.delete();
                return null;
            }
            activeRecorder.stop();
            activeRecorder.reset();
            if (finished != null && finished.exists() && finished.length() > 1000) return finished;
            if (finished != null) finished.delete();
        } catch (RuntimeException e) {
            if (finished != null) finished.delete();
        } finally {
            try { activeRecorder.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (currentAudioFile != null) {
            currentAudioFile.delete();
            currentAudioFile = null;
        }
    }

    private void stopListening() {
        if (!listening && recorder == null) {
            setStatus("Tidak sedang merekam.");
            return;
        }
        listening = false;
        handler.removeCallbacks(rotateSegment);
        File completed = stopCurrentSegment();
        if (completed != null) enqueueAudio(completed);
        updateButtons();
        setStatus(completed == null ? "Perekaman dihentikan." : "Perekaman berhenti. Memproses potongan terakhir...");
    }

    private void enqueueAudio(File audioFile) {
        networkQueue.execute(() -> {
            try {
                byte[] audio = readAll(audioFile);
                if (audio.length < 1000) return;
                String encoded = Base64.getEncoder().encodeToString(audio);
                JSONObject request = new JSONObject();
                request.put("audioBase64", encoded);
                request.put("mimeType", "audio/mp4");
                JSONObject transcribed = postJson("/api/transcribe", request);
                String transcript = transcribed.optString("transcript", "").trim();
                if (transcript.isEmpty()) throw new IOException("Transkrip kosong dari server.");
                String context;
                synchronized (transcriptLock) {
                    if (transcriptHistory.length() > 0) transcriptHistory.append("\n");
                    transcriptHistory.append(transcript);
                    if (transcriptHistory.length() > 9000) {
                        transcriptHistory.delete(0, transcriptHistory.length() - 9000);
                    }
                    context = transcriptHistory.toString();
                }
                appendOutput(transcriptView, transcript, true);
                setStatus("Transkrip masuk. Membuat saran AI...");
                JSONObject answerRequest = new JSONObject();
                answerRequest.put("transcript", transcript);
                answerRequest.put("context", context);
                answerRequest.put("topic", getSharedPreferences("listener_prefs", MODE_PRIVATE).getString("meeting_topic", ""));
                JSONObject answer = postJson("/api/answer", answerRequest);
                String answerText = answer.optString("answer", "").trim();
                if (answerText.isEmpty()) throw new IOException("Jawaban kosong dari server.");
                appendOutput(answerView, answerText, false);
                setStatus(listening ? "Sedang mendengarkan... saran terbaru siap." : "Selesai memproses audio.");
            } catch (Exception e) {
                setStatus("Proses gagal: " + safeMessage(e));
            } finally {
                if (audioFile.exists()) audioFile.delete();
            }
        });
    }

    private JSONObject postJson(String path, JSONObject payload) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl() + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String responseText = input == null ? "" : readText(input);
            JSONObject result;
            try {
                result = new JSONObject(responseText);
            } catch (Exception parseException) {
                throw new IOException("Server mengembalikan respons tidak valid (HTTP " + code + ").");
            }
            if (code < 200 || code >= 300) {
                String message = result.optString("error", "HTTP " + code);
                throw new IOException(message);
            }
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private String readText(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
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
        listening = false;
        handler.removeCallbacks(rotateSegment);
        releaseRecorder();
        networkQueue.shutdown();
        super.onDestroy();
    }
}
