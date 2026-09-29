package com.iot.viewplustest.data.network;
import com.iot.viewplustest.data.auth.AuthStore;
import android.app.Activity;
import org.json.*;
import okhttp3.*;
import java.io.IOException;

/** Restores the device-bound session when the app process restarts. */
public final class ApiClient {
    public static volatile String token;
    public static final String BASE = "http://100.104.2.114:8080";
    private static final OkHttpClient HTTP = new OkHttpClient();
    private final Activity activity;
    public interface Result { void accept(Object value, String error); }
    public ApiClient(Activity activity) { this.activity = activity; synchronized(ApiClient.class) { if(token == null) token = AuthStore.load(activity); } }
    public synchronized static void clearSession(android.content.Context context) { token = null; AuthStore.clear(context); }
    public synchronized static void saveSession(android.content.Context context, String value) throws Exception { AuthStore.save(context, value); token = value; }
    public void call(String method, String path, JSONObject body, Result result) {
        Request.Builder builder = new Request.Builder().url(BASE + path);
        final String requestToken = token;
        if (requestToken != null && !path.startsWith("/api/auth/")) builder.header("Authorization", "Bearer " + requestToken);
        if (!method.equals("GET")) builder.method(method, RequestBody.create(
            body == null ? "{}" : body.toString(), MediaType.get("application/json; charset=utf-8")));
        HTTP.newCall(builder.build()).enqueue(new Callback() {
            public void onFailure(Call call, IOException error) { deliver(null, "서버에 연결할 수 없습니다.", result); }
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String text = r.body() == null ? "" : r.body().string();
                    if (!r.isSuccessful()) {
                        String message = r.code() == 401 ? "로그인이 만료되었습니다. 다시 로그인하세요."
                                : r.code() == 403 ? "접근 권한이 없습니다. 서버가 최신 코드로 실행 중인지 확인하세요."
                                : r.code() >= 500 ? "서버에서 오류가 발생했습니다."
                                : "입력 내용을 확인하세요.";
                        try { message = new JSONObject(text).optString("message", message); } catch (Exception ignored) {}
                        if (r.code() == 401 && requestToken != null && !path.startsWith("/api/auth/")) {
                            synchronized(ApiClient.class) {
                                if (java.util.Objects.equals(token, requestToken)) clearSession(activity);
                            }
                        }
                        android.util.Log.w("ViewPlusApi", method + " " + path + " HTTP " + r.code());
                        deliver(null, message, result);
                    } else deliver(text.isEmpty() ? new JSONObject() : new JSONTokener(text).nextValue(), null, result);
                } catch (Exception e) { deliver(null, "서버 응답을 확인할 수 없습니다.", result); }
            }
        });
    }
    private void deliver(Object value, String error, Result result) {
        activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) result.accept(value, error); });
    }
}
