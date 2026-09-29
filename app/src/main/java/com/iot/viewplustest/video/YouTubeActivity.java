package com.iot.viewplustest.video;
import com.iot.viewplustest.R;

import android.os.Bundle;
import android.net.Uri;
import android.content.Intent;
import android.graphics.Color;
import android.webkit.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Only validated YouTube IDs are embedded; no application token is passed to the player.
 */
public class YouTubeActivity extends AppCompatActivity {
    private WebView player;

    public static String videoId(Uri uri) {
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(java.util.Locale.ROOT);
        String id = null;
        if (host.equals("youtu.be")) {
            if (!uri.getPathSegments().isEmpty()) id = uri.getPathSegments().get(0);
        } else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")) {
            id = uri.getQueryParameter("v");
            java.util.List<String> parts = uri.getPathSegments();
            if (id == null && parts.size() > 1 && java.util.Arrays.asList("shorts", "embed", "live").contains(parts.get(0)))
                id = parts.get(1);
        }
        return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra("videoId");
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(24, 24, 24, 24);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom);
            return insets;
        });
        com.google.android.material.button.MaterialButton back = new com.google.android.material.button.MaterialButton(this);
        back.setText("뒤로가기");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra("title"));
        title.setTextColor(Color.BLACK);
        title.setTextSize(22);
        title.setPadding(0, 24, 0, 24);
        root.addView(title);
        player = new WebView(this);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setAllowFileAccess(false);
        player.getSettings().setAllowContentAccess(false);
        player.setWebChromeClient(new WebChromeClient());
        player.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri uri = r.getUrl();
                if ("https".equals(uri.getScheme()) && "www.youtube.com".equals(uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/embed/"))
                    return false;
                return true;
            }
        });
        root.addView(player, new LinearLayout.LayoutParams(-1, (int) (240 * getResources().getDisplayMetrics().density)));
        String origin = "https://" + getPackageName();
        player.loadUrl("https://www.youtube.com/embed/" + id + "?playsinline=1", java.util.Collections.singletonMap("Referer", origin));
        TextView hint = new TextView(this);
        hint.setText("재생 버튼을 눌러 시청하세요. 앱 내 재생이 제한된 영상은 YouTube에서 열 수 있습니다.");
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, 24, 0, 12);
        root.addView(hint);
        com.google.android.material.button.MaterialButton external = new com.google.android.material.button.MaterialButton(this);
        external.setText("YouTube 에서 보기");
        external.setBackgroundColor(android.graphics.Color.parseColor("#FF0033"));
        external.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id)));
            } catch (Exception ignored) {
            }
        });
        root.addView(external);
        setContentView(root);
    }

    @Override
    protected void onPause() {
        if (player != null) player.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.onResume();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.stopLoading();
            player.destroy();
        }
        super.onDestroy();
    }
}
