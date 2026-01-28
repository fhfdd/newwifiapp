package com.example.indoornavblind.ui.activities;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;


import androidx.appcompat.app.AppCompatActivity;

import com.example.indoornavblind.R;

import java.util.Locale;

/**
 * 控制全屏设置浮层的逻辑：语速调节、语言切换、播报间隔调整
 */
public class R_SettingsActivity extends AppCompatActivity {

    private FrameLayout settingsOverlay;
    private TextView tvSpeed, tvLang, tvPace;
    private TextToSpeech tts;

    private GestureDetector gestureDetector;
    private SharedPreferences prefs;

    private float speechRate = 1.0f; // initial speech speed
    private int currentLangIndex = 0; // 0=中文, 1=English
    private final String[] languages = {"中文", "English"};
    private int paceSeconds = 3;

    private long lastTapTime = 0;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Use your main layout (with overlay included)

        settingsOverlay = findViewById(R.id.settings_fullscreen);
        tvSpeed = findViewById(R.id.tv_speed_display);
        tvLang = findViewById(R.id.tv_language_display);
        tvPace = findViewById(R.id.tv_pace_display);

        prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechRate = prefs.getFloat("speechRate", 1.0f);
        currentLangIndex = prefs.getInt("langIndex", 0);
        paceSeconds = prefs.getInt("pace", 3);

        updateDisplay();

        // Setup TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                if (currentLangIndex == 0)
                    tts.setLanguage(Locale.CHINESE);
                else
                    tts.setLanguage(Locale.ENGLISH);
            }
        });

        // Gesture detection
        gestureDetector = new GestureDetector(this, new GestureListener());

        settingsOverlay.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    // region --- Gesture control ---
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        // Detect swipe directions
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diffY = e2.getY() - e1.getY();
            float diffX = e2.getX() - e1.getX();

            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) onSwipeRight(); else onSwipeLeft();
                    return true;
                }
            } else {
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) onSwipeDown(); else onSwipeUp();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < 400) {
                closeSettings();
            }
            lastTapTime = now;
            return true;
        }
    }
    // endregion

    private void onSwipeUp() {
        speechRate = Math.min(3.0f, speechRate + 0.1f);
        updateDisplay();
        speakFeedback("语速 " + String.format(Locale.US, "%.1f", speechRate) + "倍");
    }

    private void onSwipeDown() {
        speechRate = Math.max(0.5f, speechRate - 0.1f);
        updateDisplay();
        speakFeedback("语速 " + String.format(Locale.US, "%.1f", speechRate) + "倍");
    }

    private void onSwipeLeft() {
        currentLangIndex = (currentLangIndex + 1) % languages.length;
        updateDisplay();
        speakFeedback("语言：" + languages[currentLangIndex]);
    }

    private void onSwipeRight() {
        currentLangIndex = (currentLangIndex - 1 + languages.length) % languages.length;
        updateDisplay();
        speakFeedback("语言：" + languages[currentLangIndex]);
    }

    private void updateDisplay() {
        tvSpeed.setText(String.format(Locale.US, "语速：%.1f倍", speechRate));
        tvLang.setText(String.format("语言：%s", languages[currentLangIndex]));
        tvPace.setText(String.format(Locale.US, "播报间隔：%d秒", paceSeconds));
    }

    private void speakFeedback(String text) {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(80);
        }

        if (tts != null) {
            tts.stop();
            if (currentLangIndex == 0)
                tts.setLanguage(Locale.CHINESE);
            else
                tts.setLanguage(Locale.ENGLISH);
            tts.setSpeechRate(speechRate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }

        // small visual feedback
        ObjectAnimator.ofFloat(tvSpeed, "alpha", 0.7f, 1.0f).setDuration(200).start();
    }

    private void closeSettings() {
        // Save and hide
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("speechRate", speechRate);
        editor.putInt("langIndex", currentLangIndex);
        editor.putInt("pace", paceSeconds);
        editor.apply();

        if (tts != null) tts.speak("退出设置", TextToSpeech.QUEUE_FLUSH, null, null);
        settingsOverlay.setVisibility(View.GONE);
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}