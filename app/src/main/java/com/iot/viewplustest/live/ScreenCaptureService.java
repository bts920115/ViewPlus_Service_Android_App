package com.iot.viewplustest.live;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.net.wifi.WifiManager;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 화면 공유가 앱 뒤로 가도 유지되도록 foreground service를 실행한다.
 * 채팅 플로팅 창, CPU/Wi-Fi 절전 방지 잠금도 화면 공유 수명주기와 함께 관리한다.
 */
public class ScreenCaptureService extends Service {

    /** foreground service 준비 완료를 Activity에 알리는 앱 내부 broadcast이다. */
    public static final String ACTION_FOREGROUND_READY =
            "com.iot.viewplustest.SCREEN_CAPTURE_FOREGROUND_READY";

    /** Activity가 플로팅 창에 수신 채팅을 표시할 때 사용한다. */
    public static final String ACTION_CHAT_MESSAGE = "com.iot.viewplustest.CHAT_MESSAGE";

    /** 플로팅 창이 Activity에 채팅 전송을 요청할 때 사용한다. */
    public static final String ACTION_CHAT_SEND = "com.iot.viewplustest.CHAT_SEND";

    public static final String EXTRA_SENDER = "sender";

    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID =
            "screen_capture";

    private WindowManager windowManager;

    private View overlayView;

    private TextView chatPreview;

    private TextView toggleButton;

    private LinearLayout chatComposer;

    private EditText overlayChatInput;

    private WindowManager.LayoutParams overlayParams;

    private PowerManager.WakeLock cpuWakeLock;

    private WifiManager.WifiLock wifiLock;

    private boolean chatExpanded;

    // 아이콘을 탭한 것과 드래그한 것을 구분하기 위한 좌표/상태다.
    private float touchDownRawX;
    private float touchDownRawY;
    private int overlayStartX;
    private int overlayStartY;
    private boolean overlayDragged;

    private final BroadcastReceiver chatReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CHAT_MESSAGE.equals(intent.getAction())) {
                updateChatPreview(
                        intent.getStringExtra(EXTRA_SENDER),
                        intent.getStringExtra(EXTRA_MESSAGE)
                );
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {

        super.onCreate();

        createNotificationChannel();

        IntentFilter chatFilter = new IntentFilter(ACTION_CHAT_MESSAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatReceiver, chatFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(chatReceiver, chatFilter);
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        Notification notification =
                null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(
                    this,
                    CHANNEL_ID
            )
                    .setContentTitle(
                            "화면 공유 중"
                    )
                    .setContentText(
                            "화면을 실시간으로 공유하고 있습니다."
                    )
                    .setSmallIcon(
                            android.R.drawable.ic_menu_view
                    )
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                    1001,
                    notification,
                    android.content.pm.ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );

        } else {

            startForeground(
                    1001,
                    notification
            );
        }

        showChatOverlay();
        acquireStreamingLocks();

        // MediaProjection is only legal after startForeground has completed.
        // Notify the activity so it can begin the capturer without a race.
        sendBroadcast(
                new Intent(ACTION_FOREGROUND_READY)
                        .setPackage(getPackageName())
        );

        return START_NOT_STICKY;
    }

    /**
     * 화면이 자동으로 꺼져도 CPU와 Wi-Fi가 절전 상태로 들어가지 않게 한다.
     * 이 잠금은 화면 공유 foreground service가 실행되는 동안에만 유지한다.
     */
    private void acquireStreamingLocks() {
        if (cpuWakeLock == null) {
            PowerManager powerManager = getSystemService(PowerManager.class);
            if (powerManager != null) {
                cpuWakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ViewPlus:ScreenShareCpu"
                );
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire();
            }
        }

        if (wifiLock == null) {
            WifiManager wifiManager = getApplicationContext()
                    .getSystemService(WifiManager.class);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "ViewPlus:ScreenShareWifi"
                );
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    private void releaseStreamingLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;

        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        cpuWakeLock = null;
    }

    private void showChatOverlay() {
        if (overlayView != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !Settings.canDrawOverlays(this)) {
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackground(glassBackground());

        toggleButton = new TextView(this);
        toggleButton.setText("💬");
        toggleButton.setTextColor(Color.WHITE);
        toggleButton.setTextSize(20);
        toggleButton.setGravity(Gravity.CENTER);
        toggleButton.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.addView(toggleButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
        ));

        chatPreview = new TextView(this);
        chatPreview.setText("새 메시지를 기다리는 중입니다.");
        chatPreview.setTextColor(Color.parseColor("#E8EDFF"));
        chatPreview.setTextSize(13);
        chatPreview.setMaxLines(4);
        chatPreview.setPadding(dp(4), dp(5), dp(4), dp(2));
        chatPreview.setVisibility(View.GONE);
        panel.addView(chatPreview);

        chatComposer = new LinearLayout(this);
        chatComposer.setOrientation(LinearLayout.HORIZONTAL);
        chatComposer.setPadding(0, dp(8), 0, 0);
        chatComposer.setVisibility(View.GONE);
        overlayChatInput = new EditText(this);
        overlayChatInput.setHint("메시지 입력");
        overlayChatInput.setHintTextColor(Color.parseColor("#AAB8D6"));
        overlayChatInput.setTextColor(Color.WHITE);
        overlayChatInput.setTextSize(13);
        overlayChatInput.setSingleLine(true);
        overlayChatInput.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(800)
        });
        overlayChatInput.setBackground(inputBackground());
        overlayChatInput.setPadding(dp(10), 0, dp(10), 0);
        chatComposer.addView(overlayChatInput, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView sendButton = new TextView(this);
        sendButton.setText("전송");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(13);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setPadding(dp(10), 0, dp(10), 0);
        sendButton.setOnClickListener(view -> sendOverlayChat());
        chatComposer.addView(sendButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)
        ));
        panel.addView(chatComposer);

        // 접힌 아이콘은 탭하면 채팅을 열고, 일정 거리 이상 움직이면 위치를 옮긴다.
        toggleButton.setOnTouchListener(this::handleOverlayHandleTouch);

        overlayParams = new WindowManager.LayoutParams(
                // 최초에는 접힌 채팅 아이콘만 표시한다.
                dp(56),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.END;
        overlayParams.x = dp(14);
        overlayParams.y = dp(108);
        windowManager.addView(panel, overlayParams);
        overlayView = panel;
    }

    /**
     * 플로팅 채팅의 제목/아이콘에서 터치 동작을 처리한다.
     * 짧은 탭은 접기·펼치기, 드래그는 오버레이 위치 이동으로 구분한다.
     */
    private boolean handleOverlayHandleTouch(View view, MotionEvent event) {
        if (overlayParams == null || windowManager == null || overlayView == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownRawX = event.getRawX();
                touchDownRawY = event.getRawY();
                overlayStartX = overlayParams.x;
                overlayStartY = overlayParams.y;
                overlayDragged = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - touchDownRawX;
                float deltaY = event.getRawY() - touchDownRawY;
                if (Math.abs(deltaX) > dp(6) || Math.abs(deltaY) > dp(6)) {
                    overlayDragged = true;
                    // END gravity의 x 좌표는 왼쪽 방향이 양수이므로 x 변화는 반대로 적용한다.
                    overlayParams.x = overlayStartX - Math.round(deltaX);
                    overlayParams.y = Math.max(0, overlayStartY + Math.round(deltaY));
                    windowManager.updateViewLayout(overlayView, overlayParams);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!overlayDragged) {
                    toggleChatOverlay();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return false;
        }
    }

    private void toggleChatOverlay() {
        chatExpanded = !chatExpanded;
        if (chatPreview != null) {
            chatPreview.setVisibility(chatExpanded ? View.VISIBLE : View.GONE);
        }
        if (chatComposer != null) {
            chatComposer.setVisibility(chatExpanded ? View.VISIBLE : View.GONE);
        }
        if (toggleButton != null) {
            toggleButton.setText(chatExpanded ? "💬  라이브 채팅  ▾" : "💬");
            toggleButton.setTextSize(chatExpanded ? 14 : 22);
            toggleButton.setGravity(chatExpanded ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        }
        if (overlayParams != null && windowManager != null && overlayView != null) {
            // 접혔을 때는 채팅 아이콘 하나만, 펼쳤을 때는 채팅 패널 너비를 사용한다.
            overlayParams.width = dp(chatExpanded ? 210 : 56);
            overlayParams.flags = chatExpanded
                    ? WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            windowManager.updateViewLayout(overlayView, overlayParams);
        }
    }

    private void sendOverlayChat() {
        if (overlayChatInput == null) return;
        String message = overlayChatInput.getText().toString().trim();
        if (message.isEmpty()) return;
        sendBroadcast(new Intent(ACTION_CHAT_SEND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, message));
        overlayChatInput.setText("");
    }

    private void updateChatPreview(String sender, String message) {
        if (chatPreview == null || message == null || message.trim().isEmpty()) {
            return;
        }
        chatPreview.setText((sender == null ? "참여자" : sender) + "\n" + message);
        if (!chatExpanded) {
            // 접힌 상태는 새 메시지가 와도 아이콘 UI를 유지한다.
            toggleButton.setText("💬");
        }
    }

    private GradientDrawable glassBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#A6000000"));
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), Color.parseColor("#44FFFFFF"));
        return background;
    }

    private GradientDrawable inputBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#66000000"));
        background.setCornerRadius(dp(13));
        background.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "화면 공유",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            manager.createNotificationChannel(
                    channel
            );
        }
    }

    @Override
    public IBinder onBind(Intent intent) {

        return null;
    }

    @Override
    public void onDestroy() {
        releaseStreamingLocks();
        try {
            unregisterReceiver(chatReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        super.onDestroy();
    }
}
