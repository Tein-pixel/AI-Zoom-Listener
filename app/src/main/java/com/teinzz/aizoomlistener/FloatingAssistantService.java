package com.teinzz.aizoomlistener;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


public class FloatingAssistantService extends Service {
    public static final String ACTION_SHOW = "com.teinzz.aizoomlistener.SHOW_FLOATING";
    public static final String ACTION_HIDE = "com.teinzz.aizoomlistener.HIDE_FLOATING";
    private static final String CHANNEL_ID = "orbit_assistant_overlay";
    private static final int NOTIFICATION_ID = 7102;

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private LinearLayout root;
    private LinearLayout topRow;
    private LinearLayout headingBox;
    private FrameLayout orbFrame;
    private LinearLayout panel;
    private OrbView orb;
    private TextView headline;
    private TextView status;
    private TextView answer;
    private TextView panelButton;
    private EditText transcript;
    private boolean panelOpen;
    private boolean listening;
    private boolean waitingForAnswer;
    private boolean updatingText;
    private boolean receiverRegistered;
    private boolean overlayAdded;
    private float downRawX, downRawY;
    private int downWindowX, downWindowY;
    private boolean dragging;
    private String backendUrl = "https://ai-zoom-listener.vercel.app";
    private String meetingTopic = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String type = intent.getStringExtra("type");
            String value = intent.getStringExtra("text");
            if ("status".equals(type)) {
                setStatus(value == null ? "" : value);
                if (value != null && value.contains("Jawaban AI siap")) waitingForAnswer = false;
                refreshOrb();
            } else if ("transcript".equals(type)) {
                String full = value == null ? "" : value;
                if (transcript != null && !transcript.hasFocus()) setTranscript(full);
            } else if ("answer".equals(type)) {
                waitingForAnswer = false;
                if (answer != null) answer.setText(value == null || value.trim().isEmpty()
                        ? "Belum ada jawaban. Pastikan transkrip sudah cukup jelas." : value);
                setStatus("Jawaban siap • periksa sebelum digunakan.");
                refreshOrb();
            } else if ("listening".equals(type)) {
                listening = intent.getBooleanExtra("value", false);
                if (listening) {
                    waitingForAnswer = false;
                    expandPanel(true);
                    setStatus("Mendengarkan lewat mikrofon. Ucapkan suara ke speaker HP agar lebih mudah tertangkap.");
                } else {
                    waitingForAnswer = true;
                    setStatus("Menyelesaikan transkrip, lalu meminta jawaban AI...");
                }
                refreshOrb();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        SharedPreferences prefs = getSharedPreferences("listener_prefs", MODE_PRIVATE);
        backendUrl = prefs.getString("backend_url", backendUrl);
        meetingTopic = prefs.getString("meeting_topic", "");
        listening = false;
        String oldAnswer = prefs.getString("last_answer", "");
        String oldTranscript = prefs.getString("transcript_history", "");
        registerUpdates();
        buildOverlay(oldTranscript, oldAnswer);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            if (listening) {
                Intent stop = new Intent(this, AudioListenerService.class);
                stop.setAction(AudioListenerService.ACTION_STOP);
                startService(stop);
            }
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action) || action == null) {
            SharedPreferences prefs = getSharedPreferences("listener_prefs", MODE_PRIVATE);
            backendUrl = prefs.getString("backend_url", backendUrl);
            meetingTopic = prefs.getString("meeting_topic", meetingTopic);
            startOverlayForeground();
            if (Settings.canDrawOverlays(this)) showOverlay();
            else setStatus("Aktifkan izin tampil di atas aplikasi untuk memakai bola.");
        }
        return START_STICKY;
    }

    private void startOverlayForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Bola AI mengambang", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Menjaga kontrol bola AI tetap tersedia saat pengguna memakai Meet atau Zoom.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 2, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Orbit AI siap")
                .setContentText("Bola mengambang siap disentuh untuk mulai mendengarkan.")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private void registerUpdates() {
        IntentFilter filter = new IntentFilter(AudioListenerService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updateReceiver, filter);
        receiverRegistered = true;
    }

    private void buildOverlay(String oldTranscript, String oldAnswer) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);
        root.setBackgroundColor(Color.TRANSPARENT);

        topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, 0);
        root.addView(topRow, new LinearLayout.LayoutParams(dp(48), dp(48)));

        orbFrame = new FrameLayout(this);
        orb = new OrbView(this);
        FrameLayout.LayoutParams orbParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        orbFrame.addView(orb, orbParams);
        topRow.addView(orbFrame, new LinearLayout.LayoutParams(dp(48), dp(48)));
        orbFrame.setOnTouchListener((view, event) -> handleOrbTouch(event));

        headingBox = new LinearLayout(this);
        headingBox.setOrientation(LinearLayout.VERTICAL);
        headingBox.setVisibility(View.GONE);
        headingBox.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("ORBIT AI", 12, Color.WHITE, true);
        brand.setLetterSpacing(0.11f);
        headline = label("Tekan bola untuk mulai", 10, Color.rgb(165, 190, 231), false);
        LinearLayout.LayoutParams brandP = new LinearLayout.LayoutParams(-1, -2);
        brandP.leftMargin = dp(7);
        headingBox.addView(brand, brandP);
        LinearLayout.LayoutParams headP = new LinearLayout.LayoutParams(-1, -2);
        headP.leftMargin = dp(7);
        headP.topMargin = dp(3);
        headingBox.addView(headline, headP);
        topRow.addView(headingBox, new LinearLayout.LayoutParams(0, -1, 1f));

        panelButton = label("⌃", 22, Color.WHITE, true);
        panelButton.setGravity(Gravity.CENTER);
        panelButton.setBackground(glass(Color.argb(80, 75, 112, 189), Color.argb(50, 26, 45, 80),
                Color.argb(100, 123, 161, 230), 18));
        topRow.addView(panelButton, new LinearLayout.LayoutParams(dp(36), dp(40)));
        panelButton.setText("⌄");
        panelButton.setVisibility(View.GONE);
        panelButton.setOnClickListener(v -> expandPanel(!panelOpen));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(9), dp(10), dp(10));
        panel.setBackground(glass(Color.argb(232, 15, 24, 43), Color.argb(222, 20, 31, 53),
                Color.argb(150, 70, 103, 161), 23));
        panel.setVisibility(View.GONE);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.topMargin = dp(3);
        root.addView(panel, panelParams);

        status = label("Siap. Tekan bola untuk mendengarkan.", 11, Color.rgb(172, 195, 231), false);
        status.setLineSpacing(dp(2), 1f);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        TextView transcriptTitle = label("TRANSKRIP • BISA DIEDIT", 10, Color.rgb(126, 168, 255), true);
        transcriptTitle.setLetterSpacing(0.07f);
        LinearLayout.LayoutParams tt = new LinearLayout.LayoutParams(-1, -2);
        tt.topMargin = dp(7);
        tt.bottomMargin = dp(4);
        panel.addView(transcriptTitle, tt);

        transcript = new EditText(this);
        transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        transcript.setTextColor(Color.rgb(240, 246, 255));
        transcript.setHintTextColor(Color.rgb(128, 146, 177));
        transcript.setHint("Ucapan akan muncul di sini. Koreksi teks yang salah dengar...");
        transcript.setGravity(Gravity.TOP | Gravity.START);
        transcript.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        transcript.setSingleLine(false);
        transcript.setMinLines(2);
        transcript.setMaxLines(4);
        transcript.setPadding(dp(9), dp(7), dp(9), dp(7));
        transcript.setBackground(glass(Color.argb(185, 27, 41, 65), Color.argb(180, 16, 27, 47),
                Color.argb(115, 78, 112, 171), 17));
        panel.addView(transcript, new LinearLayout.LayoutParams(-1, dp(78)));
        setTranscript(oldTranscript);

        transcript.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updatingText) return;
                String value = s == null ? "" : s.toString();
                getSharedPreferences("listener_prefs", MODE_PRIVATE).edit()
                        .putString("transcript_history", value).apply();
                if (listening) {
                    Intent edit = new Intent(FloatingAssistantService.this, AudioListenerService.class);
                    edit.setAction(AudioListenerService.ACTION_EDIT_TRANSCRIPT);
                    edit.putExtra("transcript", value);
                    startService(edit);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        TextView answerTitle = label("SARAN AI", 10, Color.rgb(126, 168, 255), true);
        answerTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
        at.topMargin = dp(8);
        at.bottomMargin = dp(4);
        panel.addView(answerTitle, at);

        ScrollView answerScroll = new ScrollView(this);
        answerScroll.setFillViewport(false);
        answer = label(oldAnswer == null || oldAnswer.trim().isEmpty()
                ? "Tekan bola lagi setelah selesai berbicara untuk membuat jawaban." : oldAnswer,
                13, Color.rgb(235, 241, 253), false);
        answer.setTextIsSelectable(true);
        answer.setLineSpacing(dp(2), 1f);
        answerScroll.addView(answer, new ScrollView.LayoutParams(-1, -2));
        panel.addView(answerScroll, new LinearLayout.LayoutParams(-1, dp(66)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(32));
        actionsParams.topMargin = dp(7);
        panel.addView(actions, actionsParams);

        TextView clear = label("Bersihkan teks", 11, Color.rgb(196, 211, 237), true);
        clear.setGravity(Gravity.CENTER);
        clear.setBackground(glass(Color.argb(90, 49, 65, 94), Color.argb(60, 21, 34, 57),
                Color.argb(100, 91, 113, 154), 13));
        actions.addView(clear, new LinearLayout.LayoutParams(0, -1, 1f));
        clear.setOnClickListener(v -> {
            Intent clearIntent = new Intent(this, AudioListenerService.class);
            clearIntent.setAction(AudioListenerService.ACTION_CLEAR);
            startService(clearIntent);
            setTranscript("");
            answer.setText("Transkrip dibersihkan.");
        });

        TextView hide = label("Sembunyikan", 11, Color.rgb(196, 211, 237), true);
        hide.setGravity(Gravity.CENTER);
        hide.setBackground(glass(Color.argb(60, 49, 65, 94), Color.argb(40, 21, 34, 57),
                Color.argb(90, 91, 113, 154), 13));
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(0, -1, 1f);
        hideParams.leftMargin = dp(7);
        actions.addView(hide, hideParams);
        hide.setOnClickListener(v -> expandPanel(false));

        windowParams = new WindowManager.LayoutParams(
                dp(48),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.RIGHT;
        windowParams.x = dp(12);
        windowParams.y = dp(190);
        windowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (!panelOpen) windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        refreshOrb();
    }

    private boolean handleOrbTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downWindowX = windowParams.x;
                downWindowY = windowParams.y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (Math.abs(dx) > dp(7) || Math.abs(dy) > dp(7)) dragging = true;
                if (dragging && overlayAdded) {
                    windowParams.x = Math.max(0, downWindowX - (int) dx);
                    windowParams.y = Math.max(dp(20), downWindowY + (int) dy);
                    try { windowManager.updateViewLayout(root, windowParams); } catch (Exception ignored) {}
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) toggleListening();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return false;
    }

    private void toggleListening() {
        if (waitingForAnswer) {
            setStatus("Jawaban sedang disiapkan. Tunggu sampai selesai.");
            return;
        }
        if (!listening) {
            SharedPreferences prefs = getSharedPreferences("listener_prefs", MODE_PRIVATE);
            backendUrl = prefs.getString("backend_url", backendUrl);
            meetingTopic = prefs.getString("meeting_topic", meetingTopic);
            Intent start = new Intent(this, AudioListenerService.class);
            start.setAction(AudioListenerService.ACTION_START);
            start.putExtra("backend_url", backendUrl);
            start.putExtra("meeting_topic", meetingTopic);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
            else startService(start);
            listening = true;
            waitingForAnswer = false;
            expandPanel(true);
            setStatus("Mengaktifkan mikrofon...");
        } else {
            Intent stop = new Intent(this, AudioListenerService.class);
            stop.setAction(AudioListenerService.ACTION_STOP);
            startService(stop);
            listening = false;
            waitingForAnswer = true;
            setStatus("Berhenti mendengarkan. Memproses transkrip lalu meminta jawaban AI...");
        }
        refreshOrb();
    }

    private int compactPanelWidth() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(260), Math.max(dp(232), screenWidth - dp(40)));
    }

    private void expandPanel(boolean show) {
        if (panel == null || root == null) return;
        panelOpen = show;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        root.setPadding(show ? dp(6) : 0, show ? dp(6) : 0,
                show ? dp(6) : 0, show ? dp(6) : 0);
        root.setBackground(show
                ? glass(Color.rgb(22, 31, 49), Color.rgb(13, 20, 34),
                    Color.rgb(60, 78, 112), 23)
                : null);
        if (!show) root.setBackgroundColor(Color.TRANSPARENT);
        if (headingBox != null) headingBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (panelButton != null) {
            panelButton.setVisibility(show ? View.VISIBLE : View.GONE);
            panelButton.setText("⌄");
        }
        if (topRow != null) {
            topRow.setPadding(show ? dp(2) : 0, 0, show ? dp(2) : 0, 0);
            ViewGroup.LayoutParams rowParams = topRow.getLayoutParams();
            rowParams.width = show ? compactPanelWidth() - dp(12) : dp(48);
            rowParams.height = show ? dp(50) : dp(48);
            topRow.setLayoutParams(rowParams);
        }
        if (orbFrame != null) {
            LinearLayout.LayoutParams orbParams = (LinearLayout.LayoutParams) orbFrame.getLayoutParams();
            orbParams.width = show ? dp(46) : dp(48);
            orbParams.height = show ? dp(46) : dp(48);
            orbFrame.setLayoutParams(orbParams);
            FrameLayout.LayoutParams inner = (FrameLayout.LayoutParams) orb.getLayoutParams();
            inner.width = show ? dp(44) : dp(46);
            inner.height = show ? dp(44) : dp(46);
            orb.setLayoutParams(inner);
        }
        if (headline != null) headline.setText(listening ? "Mendengarkan aktif" :
                (waitingForAnswer ? "AI sedang berpikir" : "Tekan bola untuk mulai"));
        if (windowParams != null) {
            windowParams.width = show ? compactPanelWidth() : dp(48);
            if (show) windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            else windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            if (overlayAdded) {
                try { windowManager.updateViewLayout(root, windowParams); } catch (Exception ignored) {}
            }
        }
        root.requestLayout();
    }

    private void showOverlay() {
        if (overlayAdded || root == null) return;
        try {
            windowManager.addView(root, windowParams);
            overlayAdded = true;
            if (panelOpen) expandPanel(true);
        } catch (Exception e) {
            getSharedPreferences("listener_prefs", MODE_PRIVATE).edit()
                    .putString("status", "Gagal menampilkan bola. Periksa izin tampil di atas aplikasi.").apply();
        }
    }

    private void removeOverlay() {
        if (overlayAdded && root != null) {
            try { windowManager.removeView(root); } catch (Exception ignored) {}
            overlayAdded = false;
        }
    }

    private void setTranscript(String value) {
        if (transcript == null) return;
        String wanted = value == null ? "" : value;
        if (transcript.getText().toString().equals(wanted)) return;
        updatingText = true;
        transcript.setText(wanted);
        transcript.setSelection(transcript.length());
        updatingText = false;
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value == null || value.trim().isEmpty()
                ? "Siap." : value);
        if (headline != null && !listening && !waitingForAnswer) {
            headline.setText(value != null && value.contains("Jawaban AI siap")
                    ? "Jawaban siap • tekan untuk mulai" : "Tekan bola untuk mulai");
        }
    }

    private void refreshOrb() {
        if (orb != null) orb.setMode(listening ? 1 : (waitingForAnswer ? 2 : 0));
        if (headline != null) headline.setText(listening ? "Mendengarkan aktif" :
                (waitingForAnswer ? "AI sedang berpikir" : "Tekan bola untuk mulai"));
    }

    private TextView label(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable glass(int top, int bottom, int stroke, int radiusDp) {
        GradientDrawable draw = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        draw.setCornerRadius(dp(radiusDp));
        draw.setStroke(dp(1), stroke);
        return draw;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        removeOverlay();
        if (receiverRegistered) {
            try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final class OrbView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase;
        private int mode;
        private final android.animation.ValueAnimator animator;

        OrbView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1550);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                phase = (Float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void setMode(int next) {
            if (mode != next) { mode = next; invalidate(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.34f;
            int core = mode == 1 ? Color.rgb(38, 149, 255) :
                    (mode == 2 ? Color.rgb(156, 105, 255) : Color.rgb(73, 129, 255));
            if (mode != 2) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(Color.argb((int)(65 * (1f - phase)), 99, 178, 255));
                canvas.drawCircle(cx, cy, radius * (1.25f + phase * 0.55f), paint);
                paint.setColor(Color.argb((int)(35 * phase), 132, 198, 255));
                canvas.drawCircle(cx, cy, radius * (1.45f + phase * 0.45f), paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Color.argb(110, 181, 151, 255));
                canvas.drawArc(cx - radius * 1.42f, cy - radius * 1.42f,
                        cx + radius * 1.42f, cy + radius * 1.42f,
                        phase * 360f, 235f, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx - radius * 0.25f, cy - radius * 0.4f,
                    radius * 2.1f,
                    new int[]{Color.argb(125, 98, 180, 255), Color.argb(20, 22, 58, 128), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius * 1.75f, paint);
            paint.setShader(null);
            paint.setShadowLayer(dp(8), 0, dp(1), Color.argb(170, Color.red(core), Color.green(core), Color.blue(core)));
            paint.setShader(new RadialGradient(cx - radius * 0.35f, cy - radius * 0.45f,
                    radius * 1.55f,
                    new int[]{Color.rgb(180, 225, 255), core, Color.rgb(11, 29, 72)},
                    new float[]{0f, 0.40f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(190, 204, 231, 255));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            if (mode == 1) {
                for (int i = 0; i < 5; i++) {
                    float height = dp(5) + dp(10) * (0.5f + 0.5f *
                            (float)Math.sin((phase * Math.PI * 2) + i * 1.15f));
                    paint.setColor(Color.WHITE);
                    canvas.drawRoundRect(cx - dp(12) + i * dp(6), cy - height / 2,
                            cx - dp(8) + i * dp(6), cy + height / 2, dp(3), dp(3), paint);
                }
            } else {
                paint.setColor(Color.argb(235, 245, 251, 255));
                canvas.drawCircle(cx - radius * 0.29f, cy - radius * 0.03f, dp(2.2f), paint);
                canvas.drawCircle(cx + radius * 0.29f, cy - radius * 0.03f, dp(2.2f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                canvas.drawArc(cx - radius * 0.24f, cy + radius * 0.10f,
                        cx + radius * 0.24f, cy + radius * 0.54f,
                        10, 160, false, paint);
            }
        }

        @Override protected void onDetachedFromWindow() {
            animator.cancel();
            super.onDetachedFromWindow();
        }
    }
}
