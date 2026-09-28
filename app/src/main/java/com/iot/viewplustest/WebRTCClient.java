package com.iot.viewplustest;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebRTC 미디어와 피어 연결을 관리한다.
 *
 * <p>송신자는 하나의 화면/마이크 트랙을 여러 시청자 피어에 붙인다. 수신자는 원격
 * 비디오 트랙을 {@link SurfaceViewRenderer}에 렌더링한다. 방 입장, 채팅, 메시지 중계는
 * 이 클래스의 책임이 아니라 {@link SignalingClient}와 {@link MainActivity}의 책임이다.</p>
 */
public class WebRTCClient {

    private static final String TAG = "WebRTCClient";
    private static final int MAX_VIEWERS = 3;
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final int CAPTURE_FPS = 30;
    private static final String STREAM_ID = "screen-stream";

    private final Context appContext;
    private final SurfaceViewRenderer renderer;
    private final SignalingClient signalingClient;
    private final Map<String, Peer> peers = new HashMap<>();

    private EglBase eglBase;
    private AudioDeviceModule audioDeviceModule;
    private PeerConnectionFactory peerConnectionFactory;
    private SurfaceTextureHelper surfaceTextureHelper;
    private ScreenCapturerAndroid screenCapturer;
    private VideoSource videoSource;
    private VideoTrack screenTrack;
    private AudioSource audioSource;
    private AudioTrack microphoneTrack;

    /** 피어마다 SDP 적용 전 도착한 ICE 후보를 임시 보관한다. */
    private static final class Peer {
        private final PeerConnection connection;
        private final List<IceCandidate> pendingCandidates = new ArrayList<>();
        private boolean remoteDescriptionSet;
        private boolean videoTrackAttached;
        private boolean audioTrackAttached;

        private Peer(PeerConnection connection) {
            this.connection = connection;
        }
    }

    public WebRTCClient(Context context, SurfaceViewRenderer renderer, SignalingClient signalingClient) {
        this.appContext = context.getApplicationContext();
        this.renderer = renderer;
        this.signalingClient = signalingClient;
        initializeWebRtc();
    }

    /** WebRTC 엔진, EGL 렌더러, 마이크 장치를 한 번 초기화한다. */
    private void initializeWebRtc() {
        eglBase = EglBase.create();
        configureAudioOutput();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions());

        audioDeviceModule = createAudioDeviceModule();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(new PeerConnectionFactory.Options())
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(new SoftwareVideoEncoderFactory())
                .setVideoDecoderFactory(new SoftwareVideoDecoderFactory())
                .createPeerConnectionFactory();

        renderer.init(eglBase.getEglBaseContext(), null);
        renderer.setEnableHardwareScaler(true);
    }

    /**
     * 현재 구현의 송신 오디오는 마이크 입력이다.
     * 내부 앱 재생음은 별도의 Android AudioPlaybackCapture 구현이 필요하다.
     */
    private AudioDeviceModule createAudioDeviceModule() {
        return JavaAudioDeviceModule.builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override
                    public void onWebRtcAudioRecordInitError(String message) {
                        Log.e(TAG, "[AUDIO-CAPTURE] 마이크 초기화 실패: " + message);
                    }

                    @Override
                    public void onWebRtcAudioRecordStartError(
                            JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode, String message) {
                        Log.e(TAG, "[AUDIO-CAPTURE] 마이크 시작 실패=" + errorCode + ": " + message);
                    }

                    @Override
                    public void onWebRtcAudioRecordError(String message) {
                        Log.e(TAG, "[AUDIO-CAPTURE] 마이크 녹음 오류: " + message);
                    }
                })
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback() {
                    @Override
                    public void onWebRtcAudioRecordStart() {
                        Log.i(TAG, "[AUDIO-CAPTURE] 마이크 녹음 시작");
                    }

                    @Override
                    public void onWebRtcAudioRecordStop() {
                        Log.i(TAG, "[AUDIO-CAPTURE] 마이크 녹음 종료");
                    }
                })
                .createAudioDeviceModule();
    }

    /** 수신음을 수화부가 아닌 기기 스피커로 출력한다. */
    private void configureAudioOutput() {
        try {
            AudioManager audioManager = appContext.getSystemService(AudioManager.class);
            if (audioManager == null) {
                return;
            }
            audioManager.requestAudioFocus(change -> { }, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
            Log.i(TAG, "[AUDIO] 스피커 출력 활성화");
        } catch (RuntimeException exception) {
            Log.w(TAG, "[AUDIO] 스피커 출력 설정 실패", exception);
        }
    }

    /** 시청 화면 터치 후 오디오 포커스를 다시 요청할 때 호출한다. */
    public void enableSpeakerOutput() {
        configureAudioOutput();
    }

    /** 서버가 전달한 ICE 설정으로 시청자 한 명과의 피어 연결을 만든다. */
    public void createPeerConnection(String peerId, JSONArray iceServersJson) {
        if (peers.containsKey(peerId)) {
            Log.d(TAG, "[PEER] 이미 존재하는 피어: " + peerId);
            return;
        }
        if (peers.size() >= MAX_VIEWERS) {
            Log.w(TAG, "[PEER] 최대 시청자 수 초과: " + peerId);
            return;
        }

        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(parseIceServers(iceServersJson));
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection connection = peerConnectionFactory.createPeerConnection(
                configuration, createPeerObserver(peerId));
        if (connection == null) {
            Log.e(TAG, "[PEER] 피어 연결 생성 실패: " + peerId);
            return;
        }

        Peer peer = new Peer(connection);
        peers.put(peerId, peer);
        attachLocalTracks(peer);
        Log.i(TAG, "[PEER] 생성: " + peerId + ", ICE 서버=" + configuration.iceServers.size());
    }

    /** JSON ICE 설정을 WebRTC SDK 타입으로 변환한다. */
    private List<PeerConnection.IceServer> parseIceServers(JSONArray iceServersJson) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        if (iceServersJson != null) {
            for (int index = 0; index < iceServersJson.length(); index++) {
                try {
                    JSONObject serverJson = iceServersJson.getJSONObject(index);
                    JSONArray urlsJson = serverJson.optJSONArray("urls");
                    List<String> urls = new ArrayList<>();
                    if (urlsJson != null) {
                        for (int urlIndex = 0; urlIndex < urlsJson.length(); urlIndex++) {
                            urls.add(urlsJson.getString(urlIndex));
                        }
                    }
                    if (urls.isEmpty()) {
                        continue;
                    }

                    PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
                    String username = serverJson.optString("username");
                    String credential = serverJson.optString("credential");
                    if (!username.isEmpty()) {
                        builder.setUsername(username);
                    }
                    if (!credential.isEmpty()) {
                        builder.setPassword(credential);
                    }
                    iceServers.add(builder.createIceServer());
                } catch (Exception exception) {
                    Log.e(TAG, "[ICE] 서버 설정 파싱 실패", exception);
                }
            }
        }

        // 서버 설정이 비었을 때도 개발 환경에서 최소한의 직접 연결을 시도한다.
        if (iceServers.isEmpty()) {
            iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer());
        }
        return iceServers;
    }

    /** 피어별 연결 상태와 원격 미디어/ICE 후보 이벤트를 처리한다. */
    private PeerConnection.Observer createPeerObserver(String peerId) {
        return new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {
                Log.d(TAG, "[SIGNAL] peer=" + peerId + " state=" + state);
            }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.i(TAG, "[ICE] peer=" + peerId + " state=" + state);
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) {
                Log.d(TAG, "[ICE] peer=" + peerId + " receiving=" + receiving);
            }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                Log.d(TAG, "[ICE] peer=" + peerId + " gathering=" + state);
            }
            @Override public void onIceCandidate(IceCandidate candidate) {
                sendCandidate(peerId, candidate);
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(MediaStream stream) {
                // 구형 Plan-B 호환 콜백. 실제 처리는 UNIFIED_PLAN의 onTrack에서 한다.
                if (!stream.videoTracks.isEmpty()) {
                    stream.videoTracks.get(0).addSink(renderer);
                }
            }
            @Override public void onRemoveStream(MediaStream stream) {
                Log.w(TAG, "[REMOTE] 스트림 제거: peer=" + peerId);
            }
            @Override public void onDataChannel(DataChannel dataChannel) { }
            @Override public void onRenegotiationNeeded() {
                Log.d(TAG, "[SDP] 재협상 필요: peer=" + peerId);
            }
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "[REMOTE] 트랙 추가: peer=" + peerId);
            }
            @Override public void onTrack(RtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().track();
                String kind = track == null ? "null" : track.kind();
                Log.i(TAG, "[REMOTE] 트랙 수신: peer=" + peerId + ", kind=" + kind);
                if (track instanceof VideoTrack) {
                    ((VideoTrack) track).addSink(renderer);
                }
            }
        };
    }

    /** ICE 후보를 Spring Boot 시그널링 서버를 통해 상대 피어로 전달한다. */
    private void sendCandidate(String peerId, IceCandidate candidate) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "candidate");
            message.put("targetId", peerId);
            message.put("sdpMid", candidate.sdpMid);
            message.put("sdpMLineIndex", candidate.sdpMLineIndex);
            message.put("candidate", candidate.sdp);
            signalingClient.send(message);

            if (candidate.sdp.contains("typ relay")) {
                Log.i(TAG, "[TURN] relay 후보 생성: peer=" + peerId);
            }
        } catch (Exception exception) {
            Log.e(TAG, "[ICE] 후보 전송 실패", exception);
        }
    }

    /** MediaProjection 권한으로 화면과 마이크 트랙을 만들고 현재 피어에 연결한다. */
    public boolean startScreenCapture(Intent screenPermissionData) {
        if (screenCapturer != null) {
            Log.d(TAG, "[CAPTURE] 이미 화면 공유 중");
            return true;
        }

        try {
            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture",
                    eglBase.getEglBaseContext());
            videoSource = peerConnectionFactory.createVideoSource(false);
            screenCapturer = new ScreenCapturerAndroid(screenPermissionData,
                    new MediaProjection.Callback() { });
            screenCapturer.initialize(surfaceTextureHelper, appContext,
                    videoSource.getCapturerObserver());
            screenCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);

            screenTrack = peerConnectionFactory.createVideoTrack("screen", videoSource);
            // Send the capture to peers only: local preview would capture itself recursively.
            createMicrophoneTrack();
            for (Peer peer : peers.values()) {
                attachLocalTracks(peer);
            }
            Log.i(TAG, "[CAPTURE] 화면 공유 시작, 연결 수=" + peers.size());
            return true;
        } catch (Exception exception) {
            Log.e(TAG, "[CAPTURE] 화면 공유 시작 실패", exception);
            releaseCaptureResources();
            return false;
        }
    }

    /** 권한이 있을 때만 마이크 트랙을 만들고, 거부되어도 영상은 계속 보낸다. */
    private void createMicrophoneTrack() {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "[AUDIO] RECORD_AUDIO 권한 없음: 영상만 전송");
            return;
        }

        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        microphoneTrack = peerConnectionFactory.createAudioTrack("microphone", audioSource);
        microphoneTrack.setEnabled(true);
        Log.i(TAG, "[AUDIO] 마이크 트랙 생성");
    }

    /** 이미 생성된 로컬 트랙을 새 피어 또는 기존 피어에 한 번만 붙인다. */
    private void attachLocalTracks(Peer peer) {
        if (screenTrack != null && !peer.videoTrackAttached) {
            peer.connection.addTrack(screenTrack, Collections.singletonList(STREAM_ID));
            peer.videoTrackAttached = true;
        }
        if (microphoneTrack != null && !peer.audioTrackAttached) {
            peer.connection.addTrack(microphoneTrack, Collections.singletonList(STREAM_ID));
            peer.audioTrackAttached = true;
        }
    }

    /** 지정된 시청자에게 offer를 만든다. 송신자만 호출해야 한다. */
    public void createOffer(String peerId) {
        Peer peer = peers.get(peerId);
        if (peer != null) {
            peer.connection.createOffer(new CreateSdpObserver(peerId, peer, "offer"),
                    new MediaConstraints());
        }
    }

    /** 현재 접속한 모든 시청자에게 화면 공유 offer를 보낸다. */
    public void createOffers() {
        for (String peerId : new ArrayList<>(peers.keySet())) {
            createOffer(peerId);
        }
    }

    public void handleOffer(String peerId, String sdp) {
        Peer peer = peers.get(peerId);
        if (peer != null) {
            peer.connection.setRemoteDescription(new SetRemoteSdpObserver(peer,
                    () -> createAnswer(peerId, peer)),
                    new SessionDescription(SessionDescription.Type.OFFER, sdp));
        }
    }

    private void createAnswer(String peerId, Peer peer) {
        peer.connection.createAnswer(new CreateSdpObserver(peerId, peer, "answer"),
                new MediaConstraints());
    }

    public void handleAnswer(String peerId, String sdp) {
        Peer peer = peers.get(peerId);
        if (peer != null) {
            peer.connection.setRemoteDescription(new SetRemoteSdpObserver(peer, null),
                    new SessionDescription(SessionDescription.Type.ANSWER, sdp));
        }
    }

    /** Remote SDP 적용 전 후보는 보관하고, 적용 뒤에는 즉시 추가한다. */
    public void handleCandidate(String peerId, String sdpMid, int sdpMLineIndex, String candidateSdp) {
        Peer peer = peers.get(peerId);
        if (peer == null) {
            return;
        }
        IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, candidateSdp);
        if (peer.remoteDescriptionSet) {
            peer.connection.addIceCandidate(candidate);
        } else {
            peer.pendingCandidates.add(candidate);
        }
    }

    /** 연결이 종료된 시청자의 리소스만 해제한다. */
    public void removePeer(String peerId) {
        Peer peer = peers.remove(peerId);
        if (peer != null) {
            peer.connection.close();
            peer.connection.dispose();
            Log.i(TAG, "[PEER] 제거: " + peerId);
        }
    }

    /** SDP 생성 성공 후 local description을 저장하고 상대 피어에게 SDP를 보낸다. */
    private final class CreateSdpObserver implements SdpObserver {
        private final String peerId;
        private final Peer peer;
        private final String messageType;

        private CreateSdpObserver(String peerId, Peer peer, String messageType) {
            this.peerId = peerId;
            this.peer = peer;
            this.messageType = messageType;
        }

        @Override public void onCreateSuccess(SessionDescription description) {
            peer.connection.setLocalDescription(new SetLocalSdpObserver(
                    () -> sendSdp(messageType, peerId, description.description)), description);
        }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) {
            Log.e(TAG, "[SDP] " + messageType + " 생성 실패: " + error);
        }
        @Override public void onSetFailure(String error) {
            Log.e(TAG, "[SDP] " + messageType + " 설정 실패: " + error);
        }
    }

    /** remote SDP 저장 완료 뒤 후보를 적용하고, 필요하면 다음 SDP 작업을 수행한다. */
    private final class SetRemoteSdpObserver implements SdpObserver {
        private final Peer peer;
        private final Runnable onSuccess;

        private SetRemoteSdpObserver(Peer peer, Runnable onSuccess) {
            this.peer = peer;
            this.onSuccess = onSuccess;
        }

        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() {
            peer.remoteDescriptionSet = true;
            for (IceCandidate candidate : peer.pendingCandidates) {
                peer.connection.addIceCandidate(candidate);
            }
            peer.pendingCandidates.clear();
            if (onSuccess != null) {
                onSuccess.run();
            }
        }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) {
            Log.e(TAG, "[SDP] remote description 설정 실패: " + error);
        }
    }

    /** local SDP 설정 완료 여부를 SDP 전송 작업에 연결한다. */
    private final class SetLocalSdpObserver implements SdpObserver {
        private final Runnable onSuccess;

        private SetLocalSdpObserver(Runnable onSuccess) {
            this.onSuccess = onSuccess;
        }

        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { onSuccess.run(); }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) {
            Log.e(TAG, "[SDP] local description 설정 실패: " + error);
        }
    }

    /** offer 또는 answer SDP를 JSON 시그널링 메시지로 만든다. */
    private void sendSdp(String type, String peerId, String sdp) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", type);
            message.put("targetId", peerId);
            message.put("sdp", sdp);
            signalingClient.send(message);
        } catch (Exception exception) {
            Log.e(TAG, "[SDP] " + type + " 전송 실패", exception);
        }
    }

    /** Activity 종료 시 WebRTC 순서에 맞춰 모든 리소스를 해제한다. */
    public void release() {
        for (String peerId : new ArrayList<>(peers.keySet())) {
            removePeer(peerId);
        }
        releaseCaptureResources();
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (audioDeviceModule != null) {
            audioDeviceModule.release();
            audioDeviceModule = null;
        }
        renderer.release();
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    /** 화면 캡처와 로컬 트랙만 해제한다. 초기화 실패 시에도 안전하게 호출할 수 있다. */
    private void releaseCaptureResources() {
        if (screenCapturer != null) {
            try {
                screenCapturer.stopCapture();
            } catch (Exception exception) {
                Log.w(TAG, "[CAPTURE] 캡처 중지 중 오류", exception);
            }
            screenCapturer.dispose();
            screenCapturer = null;
        }
        if (screenTrack != null) {
            screenTrack.dispose();
            screenTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (microphoneTrack != null) {
            microphoneTrack.dispose();
            microphoneTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
    }
}
