package com.iot.viewplustest;

import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.*;

public class MainActivity extends AppCompatActivity {
    private static final int INK = Color.rgb(24, 28, 35), MUTED = Color.rgb(101, 111, 124), ACCENT = Color.rgb(48, 87, 213);
    private ApiClient api;
    private LinearLayout page;
    private BottomNavigationView nav;
    private int tab = R.id.tab_live, generation;

    @Override
    public void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        page = findViewById(R.id.pageContent);
        nav = findViewById(R.id.bottomNavigation);
        nav.setItemActiveIndicatorEnabled(false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appRoot), (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        api = new ApiClient(this);
        nav.setOnItemSelectedListener(i -> {
            tab = i.getItemId();
            render();
            return true;
        });
        if (ApiClient.token == null) auth(false);
        else render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (api != null && ApiClient.token != null) render();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void clear(String title) {
        generation++;
        page.removeAllViews();
        label("ViewPlus", 14);
        label(title, 26);
    }

    private void label(String t, int size) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(INK);
        v.setPadding(0, dp(10), 0, dp(10));
        if (size >= 18) v.setTypeface(null, android.graphics.Typeface.BOLD);
        else v.setTextColor(MUTED);
        page.addView(v);
    }

    private EditText input(String hint, boolean secret) {
        EditText v = new EditText(this);
        v.setHint(hint);
        v.setTextColor(INK);
        v.setHintTextColor(Color.GRAY);
        v.setTextSize(14);
        v.setSingleLine(true);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(lightBox());
        v.setInputType(secret ? 129 : 1);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
        p.setMargins(0, dp(6), 0, dp(6));
        page.addView(v, p);
        return v;
    }

    private android.graphics.drawable.GradientDrawable lightBox() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.rgb(248, 249, 252));
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), Color.rgb(225, 229, 237));
        return d;
    }

    private void button(String title, Runnable action) {
        com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(this);
        boolean secondary = title.contains("새로고침") || title.contains("돌아가기") || title.contains("계정 만들기") && title.startsWith("새") || title.contains("로그아웃") || title.contains("다시 불러오기");
        b.setText(title);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setCornerRadius(dp(12));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(secondary ? Color.WHITE : ACCENT));
        b.setTextColor(secondary ? ACCENT : Color.WHITE);
        b.setStrokeWidth(secondary ? dp(1) : 0);
        b.setStrokeColor(android.content.res.ColorStateList.valueOf(Color.rgb(214, 221, 239)));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(5), 0, dp(5));
        page.addView(b, p);
    }

    private void row(String icon, String title, String subtitle, boolean active, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        TextView glyph = new TextView(this);
        glyph.setText(icon);
        glyph.setTextSize(24);
        glyph.setGravity(android.view.Gravity.CENTER);
        glyph.setTextColor(active ? ACCENT : MUTED);
        glyph.setBackground(lightBox());
        row.addView(glyph, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, dp(6), 0);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(INK);
        heading.setTextSize(16);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(heading);
        TextView meta = new TextView(this);
        meta.setText(subtitle);
        meta.setTextColor(MUTED);
        meta.setTextSize(12);
        meta.setPadding(0, dp(5), 0, 0);
        info.addView(meta);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            row.setOnClickListener(v -> action.run());
            row.setContentDescription(title + " " + subtitle);
            row.setFocusable(true);
        }
        page.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(239, 241, 245));
        page.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private String time(String value) {
        try {
            return java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"));
        } catch (Exception e) {
            return "방송 중";
        }
    }

    private JSONObject json(String... pairs) {
        JSONObject j = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) j.put(pairs[i], pairs[i + 1]);
        } catch (Exception ignored) {
        }
        return j;
    }

    private String value(EditText v) {
        return v.getText().toString();
    }

    private void error(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void call(String method, String path, JSONObject body, java.util.function.Consumer<Object> success) {
        int g = generation;
        api.call(method, path, body, (v, e) -> {
            if (g != generation) return;
            if (e != null) error(e);
            else success.accept(v);
        });
    }

    private void auth(boolean signup) {
        nav.setVisibility(View.GONE);
        clear(signup ? "회원가입" : "로그인");
        label("관심사를 함께 보고 이야기하세요.", 14);
        EditText email = input("이메일", false);
        email.setInputType(33);
        EditText nick = signup ? input("닉네임 (2~30자)", false) : null;
        EditText pw = input("비밀번호 (8자 이상)", true), confirm = signup ? input("비밀번호 확인", true) : null;
        button(signup ? "계정 만들기" : "로그인", () -> {
            if (signup && !value(pw).equals(value(confirm))) {
                error("비밀번호 확인이 일치하지 않습니다.");
                return;
            }
            JSONObject body = json("email", value(email).trim(), "password", value(pw));
            try {
                if (signup) body.put("nickname", value(nick).trim());
            } catch (Exception ignored) {
            }
            call("POST", "/api/auth/" + (signup ? "signup" : "login"), body, v -> {
                try { ApiClient.saveSession(this, ((JSONObject) v).optString("accessToken", null)); }
                catch (Exception e) { error("로그인 정보를 기기에 저장할 수 없습니다. 다시 시도해 주세요."); return; }
                tab = R.id.tab_live;
                nav.setSelectedItemId(tab);
                render();
            });
        });
        button(signup ? "로그인으로 돌아가기" : "새 계정 만들기", () -> auth(!signup));
    }

    private void render() {
        if (ApiClient.token == null) {
            auth(false);
            return;
        }
        nav.setVisibility(View.VISIBLE);
        if (tab == R.id.tab_friends) friends();
        else if (tab == R.id.tab_links) links();
        else if (tab == R.id.tab_settings) settings();
        else lives();
    }

    private void openLive(JSONObject r, boolean publisher) {
        startActivity(new Intent(this, LiveActivity.class).putExtra("roomId", r.optString("id")).putExtra("publisher", publisher));
    }

    private void lives() {
        clear("라이브");
        button("● 라이브 ON", () -> {
            EditText title = new EditText(this);
            title.setSingleLine(true);
            title.setTextColor(INK);
            title.setTextSize(15);
            title.setPadding(dp(16), dp(12), dp(16), dp(12));
            title.setBackground(lightBox());
            title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
            title.setHint("방송 제목");
            LinearLayout fields = new LinearLayout(this);
            fields.setPadding(dp(24), dp(12), dp(24), dp(8));
            fields.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("라이브 시작").setView(fields).setNegativeButton("취소", null).setPositiveButton("시작", (d, w) -> {
                if (value(title).trim().isEmpty()) {
                    error("제목을 입력하세요.");
                    return;
                }
                call("POST", "/api/rooms", json("title", value(title).trim(), "visibility", "PUBLIC"), v -> openLive((JSONObject) v, true));
            }).show();
        });
        button("새로고침", this::render);
        call("GET", "/api/rooms", null, v -> {
            JSONArray a = (JSONArray) v;
            if (a.length() == 0) label("진행 중인 방송이 없습니다.", 14);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                row("▶", r.optString("title"), "LIVE · " + r.optString("hostNickname") + "  ·  " + time(r.optString("startedAt")), true, () -> openLive(r, false));
            }
        });
    }

    private void friends() {
        clear("친구");
        EditText email = input("친구 이메일", false);
        button("친구 요청 보내기", () -> call("POST", "/api/friends/by-email", json("email", value(email).trim()), v -> {
            error("친구 요청을 보냈습니다.");
            render();
        }));
        button("새로고침", this::render);
        call("GET", "/api/friends/requests", null, v -> {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.optJSONObject(i);
                button(f.optString("nickname") + " · 요청 수락", () -> call("POST", "/api/friends/" + f.optLong("relationId") + "/accept", json(), r -> render()));
            }
        });
        call("GET", "/api/friends", null, v -> {
            JSONArray a = (JSONArray) v;
            if (a.length() == 0) label("친구를 추가해 함께 시청하세요.", 14);
            call("GET", "/api/rooms", null, r -> {
                JSONArray rooms = (JSONArray) r;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject f = a.optJSONObject(i), live = null;
                    for (int k = 0; k < rooms.length(); k++)
                        if (rooms.optJSONObject(k).optLong("hostUserId") == f.optLong("userId"))
                            live = rooms.optJSONObject(k);
                    if (live == null) row("\uD83D\uDE0A", f.optString("nickname"), "라이브 대기중", false, null);
                    else {
                        JSONObject selected = live;
                        row("▶", f.optString("nickname"), "LIVE · " + selected.optString("title"), true, () -> openLive(selected, false));
                    }
                }
            });
        });
    }

    private void links() {
        generation++;
        page.removeAllViews();
        label("ViewPlus", 14);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(this);
        heading.setText("추천 영상");
        heading.setTextSize(24);
        heading.setTextColor(INK);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        com.google.android.material.button.MaterialButton register = new com.google.android.material.button.MaterialButton(this);
        register.setText("영상등록");
        register.setTextSize(13);
        register.setAllCaps(false);
        register.setCornerRadius(dp(12));
        register.setTextColor(Color.WHITE);
        register.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        register.setOnClickListener(view -> showVideoRegistration());
        header.addView(register, new LinearLayout.LayoutParams(-2, dp(48)));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
        call("GET", "/api/video-links", null, v -> {
            JSONArray a = (JSONArray) v;
            if (a.length() == 0) label("첫 추천 영상을 등록해 보세요.", 14);
            for (int i = 0; i < a.length(); i++) {
                JSONObject link = a.optJSONObject(i);
                youtubePreview(link.optString("url"));
                row("\uD83C\uDFAC\u200B", link.optString("title"), link.optString("ownerNickname") + " · 영상 보기", true, () -> {
                    Uri uri = Uri.parse(link.optString("url"));
                    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                        error("지원하지 않는 링크입니다.");
                        return;
                    }
                    try {
                        String videoId = YouTubeActivity.videoId(uri);
                        if (videoId != null)
                            startActivity(new Intent(this, YouTubeActivity.class).putExtra("videoId", videoId).putExtra("title", link.optString("title")));
                        else startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception e) {
                        error("링크를 열 앱이 없습니다.");
                    }
                });
            }
        });
    }

    private void showVideoRegistration() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(12), dp(24), dp(8));
        EditText title = new EditText(this), url = new EditText(this);
        EditText[] inputs = {title, url};
        String[] hints = {"영상 제목", "https:// 영상 주소"};
        for (int i = 0; i < inputs.length; i++) {
            EditText field = inputs[i];
            field.setHint(hints[i]);
            field.setTextColor(INK);
            field.setHintTextColor(MUTED);
            field.setTextSize(14);
            field.setSingleLine(true);
            field.setBackground(lightBox());
            field.setPadding(dp(12), 0, dp(12), 0);
            field.setInputType(InputType.TYPE_CLASS_TEXT | (i == 1 ? InputType.TYPE_TEXT_VARIATION_URI : 0));
            field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(i == 0 ? 120 : 2048)});
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
            params.setMargins(0, 0, 0, dp(12));
            fields.addView(field, params);
        }
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("영상등록").setView(fields).setNegativeButton("취소", null)
                .setPositiveButton("등록", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            String name = value(title).trim(), address = value(url).trim();
            if (name.isEmpty()) {
                title.setError("영상 제목을 입력하세요.");
                return;
            }
            Uri uri = Uri.parse(address);
            if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null) {
                url.setError("올바른 http 또는 https 주소를 입력하세요.");
                return;
            }
            dialog.getButton(-1).setEnabled(false);
            int g = generation;
            api.call("POST", "/api/video-links", json("title", name, "url", address), (result, problem) -> {
                dialog.getButton(-1).setEnabled(true);
                if (problem != null) {
                    error(problem);
                    return;
                }
                dialog.dismiss();
                if (generation == g) render();
            });
        }));
        dialog.show();
    }

    private void youtubePreview(String url) {
        String id = YouTubeActivity.videoId(Uri.parse(url));
        if (id == null) return;
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(lightBox());
        image.setClipToOutline(true);
        image.setContentDescription("YouTube 영상 미리보기");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(180));
        params.setMargins(0, dp(18), 0, 0);
        page.addView(image, params);
        image.setOnClickListener(v -> startActivity(new Intent(this, YouTubeActivity.class).putExtra("videoId", id).putExtra("title", "추천 영상")));
        int g = generation;
        new okhttp3.OkHttpClient().newCall(new okhttp3.Request.Builder().url("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").build()).enqueue(new okhttp3.Callback() {
            public void onFailure(okhttp3.Call c, java.io.IOException e) {
            }

            public void onResponse(okhttp3.Call c, okhttp3.Response response) {
                try (okhttp3.Response result = response) {
                    if (!result.isSuccessful() || result.body() == null) return;
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(result.body().byteStream());
                    runOnUiThread(() -> {
                        if (!isDestroyed() && generation == g) image.setImageBitmap(bitmap);
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void settings() {
        clear("설정");
        TextView account = new TextView(this);
        account.setTextColor(MUTED);
        account.setText("프로필을 불러오는 중입니다.");
        page.addView(account);
        label("프로필", 18);
        EditText nick = input("닉네임 (2~30자)", false);
        button("닉네임 저장", () -> {
            String nickname = value(nick).trim();
            if (nickname.length() < 2 || nickname.length() > 30) {
                error("닉네임은 2~30자로 입력하세요.");
                return;
            }
            call("PATCH", "/api/me", json("nickname", nickname), r -> {
                nick.setText(((JSONObject) r).optString("nickname"));
                error("닉네임을 변경했습니다.");
            });
        });
        label("비밀번호 변경", 18);
        EditText current = input("현재 비밀번호", true), pw = input("새 비밀번호 (8~72자)", true), confirm = input("새 비밀번호 확인", true);
        button("비밀번호 변경", () -> {
            if (value(current).isEmpty()) {
                error("현재 비밀번호를 입력하세요.");
                return;
            }
            if (value(pw).length() < 8 || value(pw).length() > 72) {
                error("새 비밀번호는 8~72자로 입력하세요.");
                return;
            }
            if (!value(pw).equals(value(confirm))) {
                error("새 비밀번호 확인이 일치하지 않습니다.");
                return;
            }
            call("PATCH", "/api/me", json("currentPassword", value(current), "newPassword", value(pw)), r -> {
                current.setText("");
                pw.setText("");
                confirm.setText("");
                error("비밀번호를 변경했습니다.");
            });
        });
        label("계정", 18);
        button("로그아웃", () -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("로그아웃").setMessage("이 기기에서 로그아웃할까요?")
                .setNegativeButton("취소", null).setPositiveButton("로그아웃", (dialog, which) -> {
                    ApiClient.clearSession(this);
                    auth(false);
                }).show());
        button("프로필 다시 불러오기", this::render);
        call("GET", "/api/me", null, v -> {
            JSONObject me = (JSONObject) v;
            account.setText(me.optString("email"));
            nick.setText(me.optString("nickname"));
        });
    }
}
