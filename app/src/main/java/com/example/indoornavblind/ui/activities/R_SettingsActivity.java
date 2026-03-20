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
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.indoornavblind.R;
import com.example.indoornavblind.util.LanguageManager;

import java.util.Locale;

public class R_SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private FrameLayout settingsOverlay;
    private TextView tvSpeed, tvLang, tvPace, tvUnit, tvTitle, tvHint;
    private TextToSpeech tts;
    private GestureDetector gestureDetector, unitDetector;
    private SharedPreferences prefs;

    private float speechRate = 1.0f;
    private int currentLangIndex = 0; // 0 = Chinese, 1 = English
    private final String[] languages = {"中文", "English"};
    private int paceSeconds = 3;
    private boolean useSteps = true;

    private long lastTapTime = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.updateBaseContextLocale(newBase));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settingsOverlay = findViewById(R.id.settings_fullscreen);
        tvSpeed = findViewById(R.id.tv_speed_display);
        tvLang = findViewById(R.id.tv_language_display);
        tvPace = findViewById(R.id.tv_pace_display);
        tvUnit = findViewById(R.id.tv_unit_display);
        tvTitle = findViewById(R.id.tv_title);
        tvHint = findViewById(R.id.tv_hint);

        prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechRate = prefs.getFloat("speechRate", 1.0f);
        currentLangIndex = prefs.getInt("langIndex", 0);
        paceSeconds = prefs.getInt("pace", 3);
        useSteps = prefs.getBoolean("useSteps", true);

        String langCode = LanguageManager.getLanguage(this);
        currentLangIndex = "zh".equals(langCode) ? 0 : 1;

        updateDisplay();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                LanguageManager.applyTtsLanguage(tts, langCode);
                tts.setSpeechRate(speechRate);
                Log.d(TAG, "TTS initialized, language: " + langCode + ", rate: " + speechRate);
            }
        });

        gestureDetector = new GestureDetector(this, new GestureListener());

        settingsOverlay.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        unitDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 80;
            private static final int SWIPE_VELOCITY_THRESHOLD = 80;

            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @Nullable MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    switchDistanceUnit();
                    return true;
                }
                return false;
            }
        });

        tvUnit.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            unitDetector.onTouchEvent(event);
            return true;
        });
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 70;
        private static final int SWIPE_VELOCITY_THRESHOLD = 70;

        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            return true;
        }

        @Override
        public boolean onFling(@Nullable MotionEvent e1, @Nullable MotionEvent e2,
                               float velocityX, float velocityY) {
            if (e1 == null || e2 == null) return false;

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
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < 400) {
                closeSettings();
            }
            lastTapTime = now;
            return true;


        }
    }

    private void onSwipeUp() {
        speechRate = Math.min(3.0f, speechRate + 0.1f);
        updateDisplay();
        speakFeedback(getString(R.string.speed_adjusted, speechRate));
    }

    private void onSwipeDown() {
        speechRate = Math.max(0.5f, speechRate - 0.1f);
        updateDisplay();
        speakFeedback(getString(R.string.speed_adjusted, speechRate));
    }

    private void updateTtsLanguage() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();  // 先停止正在說的，避免混亂
        }

        Locale newLocale = (currentLangIndex == 0) ? Locale.CHINESE : Locale.ENGLISH;

        int result = tts.setLanguage(newLocale);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 可選：提示使用者下載語言資料
            Toast.makeText(this, "該語言資料未安裝", Toast.LENGTH_SHORT).show();
        }
    }
    private void onSwipeLeft() {
        currentLangIndex = (currentLangIndex + 1) % languages.length;
        String newLangCode = currentLangIndex == 0 ? "zh" : "en";

        LanguageManager.setLanguage(this, newLangCode);
        prefs.edit().putInt("langIndex", currentLangIndex).apply();

        updateDisplay();
        LanguageManager.applyTtsLanguage(tts, newLangCode);
        speakFeedback(getString(R.string.language_switched, languages[currentLangIndex]));

        recreate();
        updateTtsLanguage();
        speakFeedback(getString(R.string.language) + "：" + languages[currentLangIndex]);
    }

    private void onSwipeRight() {
        currentLangIndex = (currentLangIndex - 1 + languages.length) % languages.length;
        String newLangCode = currentLangIndex == 0 ? "zh" : "en";

        LanguageManager.setLanguage(this, newLangCode);
        prefs.edit().putInt("langIndex", currentLangIndex).apply();

        updateDisplay();
        LanguageManager.applyTtsLanguage(tts, newLangCode);
        speakFeedback(getString(R.string.language_switched, languages[currentLangIndex]));

        recreate();
        updateTtsLanguage();
        speakFeedback(getString(R.string.language) + "：" + languages[currentLangIndex]);
    }

    private void switchDistanceUnit() {
        useSteps = !useSteps;
        updateDisplay();

        String unit = useSteps
                ? getString(R.string.unit_steps)
                : getString(R.string.unit_meters);
        speakFeedback(getString(R.string.distance_unit_format, unit));
    }

    private void updateDisplay() {
        String langCode = LanguageManager.getLanguage(this);
        boolean isZh = "zh".equals(langCode);

        tvTitle.setText(R.string.settings_mode);

        tvSpeed.setText(getString(R.string.speech_rate_format, speechRate));

        tvLang.setText(getString(R.string.language_format, languages[currentLangIndex]));

        tvPace.setText(getString(R.string.pace_format, paceSeconds));

        // This is the critical fix: always pass the unit string
        String unit = useSteps ? getString(R.string.unit_steps) : getString(R.string.unit_meters);
        tvUnit.setText(getString(R.string.distance_unit_format, unit));

        Log.d(TAG, "Unit display set to: " + getString(R.string.distance_unit_format, unit));

        tvHint.setText(isZh ? getString(R.string.exit_hint)
                : "Swipe up/down to adjust speed\nSwipe left/right to change language\nDouble tap to exit settings");
    }

    private void speakFeedback(String text) {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(80);
            }
        }

        if (tts != null) {
            tts.stop();
            updateTtsLanguage();
            tts.setSpeechRate(speechRate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }

        ObjectAnimator.ofFloat(tvUnit, "alpha", 0.7f, 1f).setDuration(200).start();
    }

    private void closeSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("speechRate", speechRate);
        editor.putInt("langIndex", currentLangIndex);
        editor.putInt("pace", paceSeconds);
        editor.putBoolean("useSteps", useSteps);
        editor.apply();

        if (tts != null) {
            tts.speak(getString(R.string.exit_settings), TextToSpeech.QUEUE_FLUSH, null, null);
            updateTtsLanguage();
            tts.speak(currentLangIndex == 0 ? "退出设置" : "Settings closed", TextToSpeech.QUEUE_FLUSH, null, null);
        }

        settingsOverlay.setVisibility(View.GONE);
        Toast.makeText(this, getString(R.string.exit_settings), Toast.LENGTH_SHORT).show();
        finish();
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