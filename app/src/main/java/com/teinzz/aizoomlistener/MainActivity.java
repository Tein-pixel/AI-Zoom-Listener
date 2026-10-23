package com.teinzz.aizoomlistener;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION = 701;
    private static final int OVERLAY_PERMISSION = 702;
    private static final int BG = Color.rgb(7, 10, 19);
    private static final int PANEL = Color.rgb(19, 28, 46);
    private static final int TEXT = Color.rgb(242, 246, 255);
    private static final int MUTED = Color.rgb(157, 173, 197);
    private static final int BLUE = Color.rgb(92, 137, 255);

    private EditText backendInput;
    private EditText topicInput;
    private TextView statusView;
    private boolean waitingForAudioPermission;
    private boolean waitingForOverlayPermission;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        buildUi();
        SharedPreferences prefs = getSharedPreferences("listener_prefs", MODE_PRIVATE);
        backendInput.setText(prefs.getString("backend_url", "https://ai-zoom-listener.vercel.app"));
        topicInput.setText(prefs.getString("meeting_topic", ""));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(30), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView overline = label("FLOATING AI COMPANION", 10, Color.rgb(117, 162, 255), true);
        overline.setLetterSpacing(0.15f);
        page.addView(overline);

        TextView title = label("Orbit AI", 32, TEXT, true);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, -2);
        titleP.topMargin = dp(8);
        page.addView(title, titleP);

        TextView subtitle = label("Asisten rapat dalam bola mengambang. Tekan untuk mendengar, tekan lagi untuk menyelesaikan transkrip dan meminta saran AI.", 14, MUTED, false);
        subtitle.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
        subP.topMargin = dp(7);
        subP.bottomMargin = dp(14);
        page.addView(subtitle, subP);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(glass(Color.rgb(29, 42, 65), PANEL, Color.rgb(55, 76, 117), 25));
        card.setElevation(dp(4));
        page.addView(card, params(-1, -2));

        card.addView(label("BACKEND URL", 10, MUTED, true), params(-1, -2));
        backendInput = edit("https://nama-proyek.vercel.app", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams backendP = params(-1, dp(49));
        backendP.topMargin = dp(8);
        card.addView(backendInput, backendP);

        TextView apiKeyInfo = label(
                "API key tidak dimasukkan di APK. Atur di Vercel → Project Settings → Environment Variables: GEMINI_API_KEY untuk transkrip, lalu NVIDIA_API_KEY atau OPENROUTER_API_KEY untuk jawaban AI. Setelah menambahkan key, lakukan Redeploy.",
                11, Color.rgb(169, 189, 221), false);
        apiKeyInfo.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams apiKeyInfoP = params(-1, -2);
        apiKeyInfoP.topMargin = dp(10);
        card.addView(apiKeyInfo, apiKeyInfoP);

        TextView openApiSettings = label("Buka pengaturan API key di Vercel ↗", 12, Color.rgb(126, 168, 255), true);
        openApiSettings.setPadding(0, dp(9), 0, 0);
        openApiSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://vercel.com/rnr10/ai-zoom-listener/settings/environment-variables")));
            } catch (Exception ignored) {
                statusView.setText("Buka Vercel → Project Settings → Environment Variables.");
            }
        });
        card.addView(openApiSettings, params(-1, -2));

        TextView topicLabel = label("TOPIK / KONTEKS (OPSIONAL)", 10, MUTED, true);
        LinearLayout.LayoutParams topicLabelP = params(-1, -2);
        topicLabelP.topMargin = dp(15);
        card.addView(topicLabel, topicLabelP);
        topicInput = edit("Misalnya: materi biologi atau pembahasan proyek",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        topicInput.setMaxLines(2);
        LinearLayout.LayoutParams topicP = params(-1, dp(58));
        topicP.topMargin = dp(8);
        card.addView(topicInput, topicP);

        Button launch = button("Simpan & tampilkan bola AI", BLUE, Color.WHITE);
        LinearLayout.LayoutParams launchP = params(-1, dp(51));
        launchP.topMargin = dp(18);
        card.addView(launch, launchP);
        launch.setOnClickListener(v -> beginLaunch());

        statusView = label("Belum aktif. Konfigurasi hanya perlu dilakukan sekali.", 12, MUTED, false);
        statusView.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams statusP = params(-1, -2);
        statusP.topMargin = dp(13);
        card.addView(statusView, statusP);

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(dp(2), dp(19), dp(2), 0);
        page.addView(steps, params(-1, -2));
        steps.addView(label("CARA PAKAI", 10, Color.rgb(117, 162, 255), true));
        addStep(steps, "01", "Aktifkan izin mikrofon dan tampil di atas aplikasi.");
        addStep(steps, "02", "Buka Meet/Zoom dan keluarkan suara melalui speaker HP, bukan earphone, agar mikrofon dapat mendengarnya.");
        addStep(steps, "03", "Tekan bola untuk mulai. Transkrip masuk per beberapa detik dan bisa dikoreksi. Tekan bola lagi untuk berhenti dan meminta jawaban AI.");

        TextView note = label("Catatan: Android membatasi audio internal aplikasi panggilan. Bola ini memakai mikrofon; ia tidak menjamin bisa menangkap audio internal atau suara dari earphone. Gunakan hanya dengan izin peserta/guru yang sesuai.", 11, Color.rgb(125, 142, 169), false);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams noteP = params(-1, -2);
        noteP.topMargin = dp(16);
        page.addView(note, noteP);

        setContentView(scroll);
    }

    private void addStep(LinearLayout parent, String number, String message) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rowP = params(-1, -2);
        rowP.topMargin = dp(12);
        parent.addView(row, rowP);
        TextView numberView = label(number, 12, Color.rgb(117, 162, 255), true);
        row.addView(numberView, new LinearLayout.LayoutParams(dp(32), -2));
        TextView messageView = label(message, 12, MUTED, false);
        messageView.setLineSpacing(dp(2), 1f);
        row.addView(messageView, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private void beginLaunch() {
        saveConfiguration();
        if (backendInput.getText().toString().trim().isEmpty()) {
            statusView.setText("Isi URL backend terlebih dahulu.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waitingForAudioPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        requestOverlayThenLaunch();
    }

    private void requestOverlayThenLaunch() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true;
            statusView.setText("Aktifkan izin tampil di atas aplikasi, lalu kembali ke Orbit AI.");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION);
            return;
        }
        showFloatingService();
    }

    private void showFloatingService() {
        Intent intent = new Intent(this, FloatingAssistantService.class);
        intent.setAction(FloatingAssistantService.ACTION_SHOW);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        statusView.setText("Bola AI aktif. Kamu bisa kembali ke layar utama lalu membuka Meet/Zoom.");
    }

    private void saveConfiguration() {
        String url = backendInput.getText().toString().trim();
        String topic = topicInput.getText().toString().trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        getSharedPreferences("listener_prefs", MODE_PRIVATE).edit()
                .putString("backend_url", url)
                .putString("meeting_topic", topic)
                .apply();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION) {
            waitingForAudioPermission = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestOverlayThenLaunch();
            } else {
                statusView.setText("Izin mikrofon diperlukan agar bola dapat mendengar melalui speaker HP.");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION) {
            waitingForOverlayPermission = false;
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                showFloatingService();
            } else {
                statusView.setText("Izin overlay belum aktif. Aktifkan izin tersebut di pengaturan aplikasi.");
            }
        }
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText edit(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        field.setTextColor(TEXT);
        field.setHintTextColor(Color.rgb(124, 143, 171));
        field.setInputType(inputType);
        field.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setPadding(dp(14), dp(7), dp(14), dp(7));
        field.setBackground(glass(Color.rgb(35, 49, 73), Color.rgb(20, 29, 46),
                Color.rgb(61, 82, 123), 17));
        return field;
    }

    private Button button(String text, int base, int foreground) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(foreground);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setBackground(glass(Color.rgb(151, 180, 255), base, Color.rgb(183, 204, 255), 18));
        view.setElevation(dp(4));
        return view;
    }

    private GradientDrawable glass(int top, int bottom, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
