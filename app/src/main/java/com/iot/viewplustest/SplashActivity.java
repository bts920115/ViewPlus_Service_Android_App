package com.iot.viewplustest;

import android.os.*;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable enter = () -> {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.WHITE);
        int margin = dp(24);
        root.setPadding(margin, margin, margin, margin);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(margin + safe.left, margin + safe.top, margin + safe.right, margin + safe.bottom);
            return insets;
        });
        TextView name = label("ViewPlus", 28, 12, Color.rgb(24, 28, 35));
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        root.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = label("함께 보는 즐거움", 13, 10, Color.rgb(101, 111, 124));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(8);
        root.addView(subtitle, subtitleParams);
        setContentView(root);
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        handler.postDelayed(enter, 1000);
    }

    private TextView label(String text, int maxSp, int minSp, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setTextSize(maxSp);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(label, minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(enter);
        super.onDestroy();
    }
}
