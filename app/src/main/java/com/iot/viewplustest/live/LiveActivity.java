package com.iot.viewplustest.live;
import com.iot.viewplustest.MainActivity;
import com.iot.viewplustest.R;
import com.iot.viewplustest.data.network.ApiClient;
import com.iot.viewplustest.live.SignalingClient;
import com.iot.viewplustest.live.WebRTCClient;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.SurfaceViewRenderer;

/**
 * 앱의 화면을 구성하고 방 입장, 화면 공유 권한, 채팅을 조율한다.
 *
 * <p>실제 WebRTC 미디어 처리는 {@link WebRTCClient}, WebSocket 연결은
 * {@link SignalingClient}, 다른 앱 위 채팅창은 {@link ScreenCaptureService}가 맡는다.</p>
 */
public class LiveActivity extends AppCompatActivity {

    private static final String TAG = "LiveActivity";
    private static final String SERVER_URL = "http://100.104.2.114:8080";
    private static final String SIGNALING_URL = "ws://100.104.2.114:8080/ws";
    private static final int MICROPHONE_PERMISSION_REQUEST = 2001;

    private enum RoomRole {
        PUBLISHER("publisher"),
        VIEWER("viewer");

        private final String signalingValue;

        RoomRole(String signalingValue) {
            this.signalingValue = signalingValue;
        }
    }

    // 화면 요소
    private SurfaceViewRenderer remoteRenderer;
    private View shareButton;
    private EditText roomIdInput;
    private EditText chatInput;
    private LinearLayout chatMessages;
    private ScrollView chatScroll;
    private View chatContent;
    private TextView chatToggle;

    // 연결과 방 상태
    private SignalingClient signalingClient;
    private WebRTCClient webRtcClient;
    private String roomId;
    private RoomRole roomRole;
    private boolean chatExpanded = true;
    private String accessToken;

    // 화면 공유 권한 및 foreground service 상태
    private Intent screenPermissionData;
    private boolean screenShareStarted;
    private boolean leaving;
    private final android.os.Handler connectionHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable disconnectedTimeout = () -> returnToLiveList("방송 연결이 끊어졌습니다.");
    private void returnToLiveList(String message) {
        if (roomRole != RoomRole.VIEWER || leaving || isFinishing() || isDestroyed()) return;
        leaving = true;
        connectionHandler.removeCallbacksAndMessages(null);
        showToast(message);
        closeConnection();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("returnToLiveList", true));
        finish();
    }
    private boolean screenCaptureStartPending;
    private boolean screenPermissionRequestInProgress;
    private boolean waitingForOverlayPermission;
    private boolean microphonePermissionRequested;

    /** foreground service가 준비된 뒤에만 MediaProjection 캡처를 시작한다. */
    private final BroadcastReceiver foregroundReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (ScreenCaptureService.ACTION_FOREGROUND_READY.equals(intent.getAction())) {
                startCaptureAfterForegroundService();
            }
        }
    };

    /** 플로팅 채팅창에서 보낸 메시지를 현재 WebSocket 방으로 전달한다. */
    private final BroadcastReceiver overlayChatReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (ScreenCaptureService.ACTION_CHAT_SEND.equals(intent.getAction())) {
                sendChat(intent.getStringExtra(ScreenCaptureService.EXTRA_MESSAGE));
            }
        }
    };

    /** Android의 화면 공유 동의 화면 결과를 처리한다. */
    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                screenPermissionRequestInProgress = false;
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    showToast("화면 공유 권한이 거부되었습니다.");
                    finish();
                    return;
                }

                screenPermissionData = result.getData();
                Log.i(TAG, "[CAPTURE] MediaProjection 권한 획득");
                startScreenShareService();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);

        registerLocalReceivers();
        bindViews();
        bindEvents();
        accessToken = ApiClient.token;
        roomId = getIntent().getStringExtra("roomId");
        roomRole = getIntent().getBooleanExtra("publisher", false) ? RoomRole.PUBLISHER : RoomRole.VIEWER;
        if (roomRole == RoomRole.PUBLISHER) {
            remoteRenderer.setVisibility(android.view.View.GONE);
            findViewById(R.id.publisherStatus).setVisibility(android.view.View.VISIBLE);
        }
        if (accessToken == null || roomId == null) { finish(); return; }
        connectToSignalingServer();
    }

    /** 화면 공유 서비스와만 통신하는 앱 내부 receiver를 등록한다. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLocalReceivers() {
        registerNotExportedReceiver(foregroundReadyReceiver,
                new IntentFilter(ScreenCaptureService.ACTION_FOREGROUND_READY));
        registerNotExportedReceiver(overlayChatReceiver,
                new IntentFilter(ScreenCaptureService.ACTION_CHAT_SEND));
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerNotExportedReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /** XML의 UI 요소를 한곳에서 찾아 필드에 보관한다. */
    private void bindViews() {
        remoteRenderer = findViewById(R.id.remoteRenderer);
        shareButton = findViewById(R.id.shareButton);

        chatInput = findViewById(R.id.chatInput);
        chatMessages = findViewById(R.id.chatMessages);
        chatScroll = findViewById(R.id.chatScroll);
        chatContent = findViewById(R.id.chatContent);
        chatToggle = findViewById(R.id.chatToggle);
    }

    /** 버튼, 키보드, 수신 화면 터치 이벤트를 등록한다. */
    @SuppressLint("ClickableViewAccessibility")
    private void bindEvents() {
        findViewById(R.id.sendChatButton).setOnClickListener(v -> sendChatFromInput());
        shareButton.setOnClickListener(v -> finish());
        chatToggle.setOnClickListener(v -> toggleChatPanel());

        chatInput.setOnEditorActionListener((view, actionId, event) -> {
            sendChatFromInput();
            return true;
        });
        remoteRenderer.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && webRtcClient != null) {
                webRtcClient.enableSpeakerOutput();
                showToast("라이브 소리 출력을 활성화했습니다.");
            }
            return true;
        });
    }

    /** 다른 앱 위 표시 권한 화면에서 돌아온 경우 화면 공유 권한을 다시 요청한다. */
    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForOverlayPermission && canDrawOverlay()) {
            waitingForOverlayPermission = false;
            requestScreenCapture();
        }
    }

    /** 입력값을 검증한 뒤 publisher 또는 viewer 역할로 WebSocket에 연결한다. */
    private void joinRoom(RoomRole requestedRole) {
        String requestedRoomId = roomIdInput.getText().toString().trim();
        if (requestedRoomId.isEmpty()) {
            showToast("방 번호를 입력하세요.");
            return;
        }

        roomId = requestedRoomId;
        roomRole = requestedRole;
        shareButton.setVisibility(roomRole == RoomRole.PUBLISHER ? View.VISIBLE : View.GONE);
        connectToSignalingServer();
    }

    /** 이전 연결을 정리하고 새 WebSocket/WebRTC 객체를 만든다. */
    private void connectToSignalingServer() {
        closeConnection();

        signalingClient = new SignalingClient(new SignalingClient.Listener() {
            @Override
            public void onMessage(JSONObject message) {
                runOnUiThread(() -> handleSignal(message));
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    if (leaving || isFinishing() || isDestroyed()) return;
                    showToast("서버에 연결했습니다.");
                    sendJoinMessage();
                    if (roomRole == RoomRole.PUBLISHER) requestScreenCapture();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> returnToLiveList("서버 연결이 종료되었습니다."));
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "[SIGNAL] 연결 실패: " + message);
                runOnUiThread(() -> { if (roomRole == RoomRole.VIEWER) returnToLiveList("방송 연결이 끊어졌습니다."); else showToast("서버 연결 실패: " + message); });
            }
        });
        webRtcClient = new WebRTCClient(this, remoteRenderer, signalingClient);
        webRtcClient.setConnectionListener(state -> runOnUiThread(() -> {
            if (roomRole != RoomRole.VIEWER || leaving || isFinishing() || isDestroyed()) return;
            if (state == org.webrtc.PeerConnection.IceConnectionState.FAILED) returnToLiveList("영상 연결이 종료되었습니다.");
            else if (state == org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED) {
                connectionHandler.removeCallbacks(disconnectedTimeout);
                connectionHandler.postDelayed(disconnectedTimeout, 5000);
            } else if (state == org.webrtc.PeerConnection.IceConnectionState.CONNECTED || state == org.webrtc.PeerConnection.IceConnectionState.COMPLETED) connectionHandler.removeCallbacks(disconnectedTimeout);
        }));
        if (accessToken == null) {
            showToast("로그인 후 라이브에 참여할 수 있습니다.");
            return;
        }
        signalingClient.connect(SIGNALING_URL, accessToken);
    }

    /** 서버의 방 관리 규격에 맞춘 join 메시지이다. */
    private void sendJoinMessage() {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "join");
            message.put("roomId", roomId);
            message.put("role", roomRole.signalingValue);
            if (!signalingClient.send(message)) showToast("서버에 연결되지 않았습니다.");
        } catch (Exception exception) {
            Log.e(TAG, "[SIGNAL] 방 입장 메시지 생성 실패", exception);
        }
    }

    /** 시그널링 메시지를 유형별로 WebRTC, 채팅, UI에 위임한다. */
    private void handleSignal(JSONObject message) {
        if (leaving || isFinishing() || isDestroyed()) return;
        String type = message.optString("type");
        Log.d(TAG, "[SIGNAL] 수신: " + type);

        try {
            switch (type) {
                case "waiting":
                    showToast("상대방을 기다리는 중입니다.");
                    break;
                case "peer-ready":
                    handlePeerReady(message);
                    break;
                case "offer":
                    webRtcClient.handleOffer(message.getString("peerId"), message.getString("sdp"));
                    break;
                case "answer":
                    webRtcClient.handleAnswer(message.getString("peerId"), message.getString("sdp"));
                    break;
                case "candidate":
                    webRtcClient.handleCandidate(message.getString("peerId"),
                            message.getString("sdpMid"), message.getInt("sdpMLineIndex"),
                            message.getString("candidate"));
                    break;
                case "peer-left":
                    webRtcClient.removePeer(message.optString("peerId"));
                    if (roomRole == RoomRole.VIEWER) returnToLiveList("방송이 종료되었습니다.");
                    else showToast("시청자가 나갔습니다.");
                    break;
                case "chat":
                    addChatMessage("참여자", message.optString("message"), false);
                    break;
                case "error":
                    showToast(message.optString("message", "방 접속 오류"));
                    break;
                default:
                    Log.w(TAG, "[SIGNAL] 알 수 없는 메시지: " + message);
            }
        } catch (Exception exception) {
            Log.e(TAG, "[SIGNAL] 메시지 처리 실패", exception);
        }
    }

    /** 피어가 준비되면 연결을 만들고 publisher만 화면 공유/offer 생성을 시작한다. */
    private void handlePeerReady(JSONObject message) throws Exception {
        String peerId = message.getString("peerId");
        boolean shouldCreateOffer = message.getBoolean("initiator");
        JSONArray iceServers = message.optJSONArray("iceServers");
        webRtcClient.createPeerConnection(peerId, iceServers);

        if (!shouldCreateOffer) {
            return;
        }
        if (screenShareStarted) {
            webRtcClient.createOffer(peerId);
        } else {
            requestScreenCapture();
        }
    }

    /** 화면 공유 전에 오버레이, 마이크, MediaProjection 권한을 순서대로 요청한다. */
    private void requestScreenCapture() {
        if (webRtcClient == null || roomRole != RoomRole.PUBLISHER) {
            showToast("화면 송신자로 방에 입장한 뒤 사용할 수 있습니다.");
            return;
        }
        if (!canDrawOverlay()) {
            requestOverlayPermission();
            return;
        }
        if (needsMicrophonePermission()) {
            requestMicrophonePermission();
            return;
        }
        if (screenShareStarted || screenPermissionRequestInProgress) {
            return;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            showToast("화면 공유 기능을 사용할 수 없습니다.");
            return;
        }
        screenPermissionRequestInProgress = true;
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent());
    }

    private boolean canDrawOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        waitingForOverlayPermission = true;
        showToast("다른 앱 위에서 채팅을 보려면 표시 권한을 허용하세요.");
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean needsMicrophonePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                && !microphonePermissionRequested;
    }

    private void requestMicrophonePermission() {
        microphonePermissionRequested = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
    }

    /** 마이크 거부는 영상 공유를 막지 않는다. 권한 결과 뒤 화면 공유 절차를 이어간다. */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MICROPHONE_PERMISSION_REQUEST) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            showToast("마이크 권한이 없어 영상만 공유합니다.");
        }
        requestScreenCapture();
    }

    /** Foreground service가 먼저 실행되어 Android의 MediaProjection 규칙을 지킨다. */
    private void startScreenShareService() {
        if (screenShareStarted || screenCaptureStartPending || screenPermissionData == null) {
            return;
        }
        screenCaptureStartPending = true;
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /** service의 startForeground 완료 broadcast 뒤 실제 WebRTC 캡처를 시작한다. */
    private void startCaptureAfterForegroundService() {
        if (!screenCaptureStartPending || screenPermissionData == null || webRtcClient == null) {
            return;
        }
        screenCaptureStartPending = false;
        if (!webRtcClient.startScreenCapture(screenPermissionData)) {
            showToast("화면 캡처를 시작하지 못했습니다.");
            return;
        }

        screenShareStarted = true;
        ((TextView) findViewById(R.id.publisherStatus)).setText("실시간 LIVE 진행중");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        webRtcClient.createOffers();
        Log.i(TAG, "[CAPTURE] 화면 공유 및 offer 생성 시작");
    }

    /** 채팅 입력창 값을 전송하고 내 메시지를 즉시 화면에 표시한다. */
    private void sendChatFromInput() {
        sendChat(chatInput.getText().toString().trim());
    }

    private void sendChat(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (signalingClient == null || roomId == null) {
            showToast("먼저 방에 입장하세요.");
            return;
        }

        try {
            JSONObject message = new JSONObject();
            message.put("type", "chat");
            message.put("message", text);
            if (!signalingClient.send(message)) { showToast("메시지를 전송하지 못했습니다."); return; }
            addChatMessage("나", text, true);
            chatInput.setText("");
        } catch (Exception exception) {
            Log.e(TAG, "[CHAT] 전송 실패", exception);
        }
    }

    /** 채팅 메시지를 화면과, 화면 공유 중인 플로팅 창에 함께 표시한다. */
    private void addChatMessage(String sender, String message, boolean mine) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        TextView bubble = new TextView(this);
        bubble.setText(sender + "  " + message);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14);
        bubble.setShadowLayer(5, 0, 2, Color.BLACK);
        bubble.setPadding(dp(12), dp(7), dp(12), dp(7));
        bubble.setBackground(createChatBubbleBackground(mine));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        params.gravity = mine ? Gravity.END : Gravity.START;
        chatMessages.addView(bubble, params);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));

        Intent overlayMessage = new Intent(ScreenCaptureService.ACTION_CHAT_MESSAGE)
                .setPackage(getPackageName())
                .putExtra(ScreenCaptureService.EXTRA_SENDER, sender)
                .putExtra(ScreenCaptureService.EXTRA_MESSAGE, message);
        sendBroadcast(overlayMessage);
    }

    private GradientDrawable createChatBubbleBackground(boolean mine) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor(mine ? "#CC3057D5" : "#99000000"));
        background.setCornerRadius(dp(14));
        return background;
    }

    /** 채팅 패널을 접거나 펼친다. */
    private void toggleChatPanel() {
        chatExpanded = !chatExpanded;
        chatContent.setVisibility(chatExpanded ? View.VISIBLE : View.GONE);
        chatToggle.setText(chatExpanded ? "라이브 채팅  ·  접기" : "라이브 채팅  ·  펼치기");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 새 방 연결 전 또는 Activity 종료 시 연결 객체를 안전하게 정리한다. */
    private void closeConnection() {
        if (webRtcClient != null) {
            webRtcClient.release();
            webRtcClient = null;
        }
        if (signalingClient != null) {
            signalingClient.close();
            signalingClient = null;
        }
    }

    @Override
    protected void onDestroy() {
        leaving = true;
        connectionHandler.removeCallbacksAndMessages(null);
        unregisterReceiverSafely(foregroundReadyReceiver);
        unregisterReceiverSafely(overlayChatReceiver);
        closeConnection();
        if (roomRole == RoomRole.PUBLISHER) stopService(new Intent(this, ScreenCaptureService.class));
        if (roomRole == RoomRole.PUBLISHER && roomId != null)
            new ApiClient(this).call("POST", "/api/rooms/" + roomId + "/end", new JSONObject(), (value, error) -> {});
        super.onDestroy();
    }

    private void unregisterReceiverSafely(BroadcastReceiver receiver) {
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Activity가 receiver 등록 전에 종료될 수 있다.
        }
    }
}
