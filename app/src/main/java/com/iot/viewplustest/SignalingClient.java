package com.iot.viewplustest;

import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Spring Boot 시그널링 서버와 JSON 메시지를 주고받는 WebSocket 어댑터다.
 * WebRTC SDP와 ICE 후보의 의미는 알지 못하며, 연결과 전달만 담당한다.
 */
public class SignalingClient {

    private static final String TAG = "SignalingClient";

    /** WebSocket 수명주기를 Activity에 알리는 콜백이다. */
    public interface Listener {
        void onMessage(JSONObject message);
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    private final Listener listener;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;

    public SignalingClient(Listener listener) {
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // WebSocket은 서버가 메시지를 보낼 때까지 계속 열려 있어야 한다.
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /** 지정한 ws:// 또는 wss:// 주소로 연결한다. */
    public void connect(String signalingUrl, String accessToken) {
        Log.i(TAG, "[WS] 연결 시도: " + signalingUrl);
        Request request = new Request.Builder().url(signalingUrl)
                .header("Authorization", "Bearer " + accessToken).build();
        webSocket = httpClient.newWebSocket(request, new SocketListener());
    }

    /** JSON을 문자열로 직렬화하여 서버에 전송한다. */
    public boolean send(JSONObject message) {
        if (webSocket == null) {
            Log.w(TAG, "[WS] 연결 전 메시지 전송 시도: " + message.optString("type"));
            return false;
        }

        String text = message.toString();
        Log.d(TAG, "[WS-OUT] " + summarize(text));
        return webSocket.send(text);
    }

    /** 현재 WebSocket을 정상 종료한다. */
    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "activity closed");
            webSocket = null;
        }
    }

    /** OkHttp 콜백을 Listener 인터페이스로 변환한다. */
    private final class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket socket, Response response) {
            Log.i(TAG, "[WS] 연결 성공");
            listener.onConnected();
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            Log.d(TAG, "[WS-IN] " + summarize(text));
            try {
                listener.onMessage(new JSONObject(text));
            } catch (Exception exception) {
                Log.e(TAG, "[WS] JSON 파싱 실패", exception);
            }
        }

        @Override
        public void onFailure(WebSocket socket, Throwable throwable, Response response) {
            String message = throwable.getMessage() == null
                    ? "알 수 없는 WebSocket 오류"
                    : throwable.getMessage();
            Log.e(TAG, "[WS] 연결 실패: " + message, throwable);
            listener.onError(message);
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            Log.i(TAG, "[WS] 연결 종료: " + code + " / " + reason);
            listener.onDisconnected();
        }
    }

    /** SDP와 ICE 전체 내용을 로그에 남기지 않고 메시지 구조만 기록한다. */
    private String summarize(String text) {
        try {
            JSONObject json = new JSONObject(text);
            return "type=" + json.optString("type", "unknown")
                    + " peer=" + json.optString("peerId", "-")
                    + " target=" + json.optString("targetId", "-")
                    + " sdpLength=" + json.optString("sdp", "").length()
                    + " candidateLength=" + json.optString("candidate", "").length();
        } catch (Exception ignored) {
            return "non-json length=" + text.length();
        }
    }
}
