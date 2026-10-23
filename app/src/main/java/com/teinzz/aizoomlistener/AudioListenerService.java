package com.teinzz.aizoomlistener;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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

public class AudioListenerService extends Service {
    public static final String ACTION_START = "com.teinzz.aizoomlistener.START";
    public static final String ACTION_STOP = "com.teinzz.aizoomlistener.STOP";
    public static final String ACTION_CLEAR = "com.teinzz.aizoomlistener.CLEAR";
    public static final String ACTION_UPDATE = "com.teinzz.aizoomlistener.UPDATE";

    private static final String CHANNEL_ID = "ai_zoom_listener_recording";
    private static final int NOTIFICATION_ID = 7101;
    private static final long SEGMENT_MS = 12000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkQueue = Executors.newSingleThreadExecutor();
    private final Object historyLock = new Object();
    private final Object taskLock = new Object();
    private final StringBuilder transcriptHistory = new StringBuilder();

    private String backendUrl = "";
    private String meetingTopic = "";
    private MediaRecorder recorder;
    private File currentAudioFile;
    private long segmentStartedAt;
    private volatile boolean listening;
    private volatile boolean stopRequested;
    private int pendingTasks;

    private final Runnable rotateSegment = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            File completed = stopCurrentSegment();
            if (listening) startSegment();
            if (completed != null) enqueueAudio(completed);
            if (listening) {
                handler.postDelayed(this, SEGMENT_MS);
            } else {
                stopRequested = true;
                maybeFinishStop();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_CLEAR.equals(action)) {
            synchronized (historyLock) { transcriptHistory.setLength(0); }
            getPrefs().edit().remove("transcript_history").remove("last_answer").apply();
            if (!listening) stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopCapture();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String configuredUrl = intent.getStringExtra("backend_url");
            String configuredTopic = intent.getStringExtra("meeting_topic");
            if (configuredUrl != null) backendUrl = stripTrailingSlash(configuredUrl);
            if (configuredTopic != null) meetingTopic = configuredTopic;

            if (!listening) {
                stopRequested = false;
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                listening = true;
                getPrefs().edit().putBoolean("is_listening", true).apply();
                emit("listening", "", true);
                setStatus("Mikrofon aktif. Buka Zoom; rekaman berjalan melalui notifikasi layanan.");
                startSegment();
                if (listening) handler.postDelayed(rotateSegment, SEGMENT_MS);
                else {
                    stopRequested = true;
                    maybeFinishStop();
                }
            }
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AI-Zoom Listener", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Menampilkan status mikrofon AI-Zoom Listener saat merekam.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AI-Zoom Listener sedang aktif")
                .setContentText("Mendengarkan melalui mikrofon dan memproses audio per 12 detik.")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void startSegment() {
        if (!listening) return;
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
            getPrefs().edit().putBoolean("is_listening", false).apply();
            emit("listening", "", false);
            setStatus("Gagal memulai mikrofon: " + safeMessage(e));
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

    private void stopCapture() {
        if (!listening) {
            stopRequested = true;
            maybeFinishStop();
            return;
        }
        listening = false;
        handler.removeCallbacks(rotateSegment);
        File completed = stopCurrentSegment();
        getPrefs().edit().putBoolean("is_listening", false).apply();
        emit("listening", "", false);
        setStatus(completed == null
                ? "Perekaman dihentikan. Menyelesaikan proses audio yang tersisa..."
                : "Perekaman dihentikan. Memproses potongan audio terakhir...");
        if (completed != null) enqueueAudio(completed);
        stopRequested = true;
        maybeFinishStop();
    }

    private void enqueueAudio(File audioFile) {
        synchronized (taskLock) { pendingTasks++; }
        networkQueue.execute(() -> {
            try {
                byte[] bytes = readAll(audioFile);
                if (bytes.length < 1000) return;
                String audioBase64 = Base64.getEncoder().encodeToString(bytes);
                JSONObject request = new JSONObject();
                request.put("audioBase64", audioBase64);
                request.put("mimeType", "audio/mp4");

                JSONObject transcribed = postJson("/api/transcribe", request);
                String transcript = transcribed.optString("transcript", "").trim();
                if (transcript.isEmpty()) return;

                String context;
                synchronized (historyLock) {
                    if (transcriptHistory.length() > 0) transcriptHistory.append("\n");
                    transcriptHistory.append(transcript);
                    if (transcriptHistory.length() > 9000) {
                        transcriptHistory.delete(0, transcriptHistory.length() - 9000);
                    }
                    context = transcriptHistory.toString();
                }
                getPrefs().edit().putString("transcript_history", context).apply();
                emit("transcript", transcript, false);
                setStatus("Transkrip masuk. Membuat saran AI...");

                JSONObject answerRequest = new JSONObject();
                answerRequest.put("transcript", transcript);
                answerRequest.put("context", context);
                answerRequest.put("topic", meetingTopic);
                JSONObject answer = postJson("/api/answer", answerRequest);
                String answerText = answer.optString("answer", "").trim();
                if (!answerText.isEmpty()) {
                    getPrefs().edit().putString("last_answer", answerText).apply();
                    emit("answer", answerText, false);
                    setStatus(listening ? "Sedang mendengarkan... saran terbaru siap." : "Selesai memproses potongan audio.");
                }
            } catch (Exception e) {
                setStatus("Proses gagal: " + safeMessage(e));
            } finally {
                if (audioFile.exists()) audioFile.delete();
                synchronized (taskLock) { pendingTasks--; }
                maybeFinishStop();
            }
        });
    }

    private JSONObject postJson(String path, JSONObject payload) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(backendUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String responseText = stream == null ? "" : readText(stream);
            JSONObject result;
            try { result = new JSONObject(responseText); }
            catch (Exception e) { throw new IOException("Respons backend tidak valid (HTTP " + code + ")."); }
            if (code < 200 || code >= 300) {
                throw new IOException(result.optString("error", "HTTP " + code));
            }
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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

    private SharedPreferences getPrefs() {
        return getSharedPreferences("listener_prefs", MODE_PRIVATE);
    }

    private void setStatus(String message) {
        getPrefs().edit().putString("status", message).apply();
        emit("status", message, false);
    }

    private void emit(String type, String text, boolean value) {
        Intent update = new Intent(ACTION_UPDATE);
        update.setPackage(getPackageName());
        update.putExtra("type", type);
        update.putExtra("text", text == null ? "" : text);
        if ("listening".equals(type)) update.putExtra("value", value);
        sendBroadcast(update);
    }

    private void maybeFinishStop() {
        boolean shouldFinish;
        synchronized (taskLock) { shouldFinish = stopRequested && pendingTasks == 0; }
        if (!shouldFinish) return;
        handler.post(() -> {
            if (stopRequested && !listening) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 260 ? message.substring(0, 260) : message;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(rotateSegment);
        releaseRecorder();
        networkQueue.shutdown();
        super.onDestroy();
    }
}
