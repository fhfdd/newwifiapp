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
    private GestureDetector gestureDetector;
    private GestureDetector paceDetector;
    private GestureDetector unitDetector;
    private SharedPreferences prefs;

    private float speechRate = 1.0f;
    private int currentLangIndex = 0; // 0 = 中文, 1 = English
    private final String[] languages = {"中文", "English"};
    private int paceSeconds = 3; // 3, 5, 7 seconds
    private boolean useCm = false; // false = 步数, true = 厘米

    // Pace options
    private static final int[] PACE_OPTIONS = {3, 5, 7};

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
        useCm = prefs.getBoolean("useCm", false);

        updateDisplay();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                String langCode = (currentLangIndex == 0) ? "zh" : "en";
                LanguageManager.applyTtsLanguage(tts, langCode);
                tts.setSpeechRate(speechRate);
            }
        });

        // Main gesture detector for speed and language
        gestureDetector = new GestureDetector(this, new MainGestureListener());
        settingsOverlay.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        // Pace gesture detector for pace adjustment
        paceDetector = new GestureDetector(this, new PaceGestureListener());
        tvPace.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            paceDetector.onTouchEvent(event);
            return true;
        });

        // Unit gesture detector for distance unit
        unitDetector = new GestureDetector(this, new UnitGestureListener());
        tvUnit.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            unitDetector.onTouchEvent(event);
            return true;
        });
    }

    private class MainGestureListener extends GestureDetector.SimpleOnGestureListener {
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
                // Horizontal swipe - change language
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onSwipeRight();
                    } else {
                        onSwipeLeft();
                    }
                    return true;
                }
            } else {
                // Vertical swipe - change speech rate
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        onSwipeDown();
                    } else {
                        onSwipeUp();
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            closeSettings();
            return true;
        }
    }

    private class PaceGestureListener extends GestureDetector.SimpleOnGestureListener {
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
                // Swipe on pace area - change pace interval
                if (diffX > 0) {
                    onPaceIncrease();
                } else {
                    onPaceDecrease();
                }
                return true;
            }
            return false;
        }
    }

    private class UnitGestureListener extends GestureDetector.SimpleOnGestureListener {
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
    }

    // ==================== Speech Rate Methods ====================

    private void onSwipeUp() {
        speechRate = Math.min(3.0f, speechRate + 0.1f);
        updateDisplay();
        if (tts != null) {
            tts.setSpeechRate(speechRate);
        }
        String feedback = getString(R.string.speed_adjusted, speechRate);
        speakFeedback(feedback);
    }

    private void onSwipeDown() {
        speechRate = Math.max(0.5f, speechRate - 0.1f);
        updateDisplay();
        if (tts != null) {
            tts.setSpeechRate(speechRate);
        }
        String feedback = getString(R.string.speed_adjusted, speechRate);
        speakFeedback(feedback);
    }

    // ==================== Language Methods ====================

    private void onSwipeLeft() {
        // Left swipe: cycle to next language
        currentLangIndex = (currentLangIndex + 1) % languages.length;
        applyLanguageChange();
    }

    private void onSwipeRight() {
        // Right swipe: cycle to previous language
        currentLangIndex = (currentLangIndex - 1 + languages.length) % languages.length;
        applyLanguageChange();
    }

    private void applyLanguageChange() {
        String newLangCode = currentLangIndex == 0 ? "zh" : "en";

        LanguageManager.setLanguage(this, newLangCode);
        prefs.edit().putInt("langIndex", currentLangIndex).apply();

        updateDisplay();
        LanguageManager.applyTtsLanguage(tts, newLangCode);

        String feedback = getString(R.string.language_switched, languages[currentLangIndex]);
        speakFeedback(feedback);

        recreate();
    }

    // ==================== Pace Methods ====================

    private void onPaceIncrease() {
        int currentIndex = getPaceIndex();
        int newIndex = (currentIndex + 1) % PACE_OPTIONS.length;
        paceSeconds = PACE_OPTIONS[newIndex];
        updateDisplay();

        boolean isZh = currentLangIndex == 0;
        String feedback;
        if (isZh) {
            feedback = "播报间隔已调整为 " + paceSeconds + " 秒";
        } else {
            feedback = "Interval adjusted to " + paceSeconds + " seconds";
        }
        speakFeedback(feedback);
    }

    private void onPaceDecrease() {
        int currentIndex = getPaceIndex();
        int newIndex = (currentIndex - 1 + PACE_OPTIONS.length) % PACE_OPTIONS.length;
        paceSeconds = PACE_OPTIONS[newIndex];
        updateDisplay();

        boolean isZh = currentLangIndex == 0;
        String feedback;
        if (isZh) {
            feedback = "播报间隔已调整为 " + paceSeconds + " 秒";
        } else {
            feedback = "Interval adjusted to " + paceSeconds + " seconds";
        }
        speakFeedback(feedback);
    }

    private int getPaceIndex() {
        for (int i = 0; i < PACE_OPTIONS.length; i++) {
            if (PACE_OPTIONS[i] == paceSeconds) {
                return i;
            }
        }
        return 0;
    }

    // ==================== Distance Unit Methods ====================

    private void switchDistanceUnit() {
        useCm = !useCm;
        prefs.edit().putBoolean("useCm", useCm).apply();
        updateDisplay();

        boolean isZh = currentLangIndex == 0;
        String unitName = useCm ? (isZh ? "厘米" : "cm") : (isZh ? "步数" : "steps");
        String feedback;
        if (isZh) {
            feedback = "距离单位已切换为 " + unitName;
        } else {
            feedback = "Distance unit changed to " + unitName;
        }
        speakFeedback(feedback);
    }

    // ==================== Display Update Methods ====================

    private void updateDisplay() {
        boolean isZh = currentLangIndex == 0;

        tvTitle.setText(R.string.settings_mode);
        tvSpeed.setText(getString(R.string.speech_rate_format, speechRate));
        tvLang.setText(getString(R.string.language_format, languages[currentLangIndex]));
        tvPace.setText(getString(R.string.pace_format, paceSeconds));

        // Distance unit display
        String unitName = useCm ? (isZh ? "厘米" : "cm") : (isZh ? "步数" : "steps");
        String unitText = getString(R.string.distance_unit_format, unitName);
        tvUnit.setText(unitText);

        Log.d(TAG, "Display updated - Language: " + languages[currentLangIndex] +
                ", Pace: " + paceSeconds + "s, Unit: " + unitName + ", useCm: " + useCm);

        // Hint text
        if (isZh) {
            tvHint.setText("上下滑动：调节语速\n左右滑动：切换语言\n左右滑动间隔区域：切换间隔\n左右滑动单位区域：切换步/厘米\n双击：退出设置");
        } else {
            tvHint.setText("Swipe up/down: Adjust speed\nSwipe left/right: Change language\nSwipe pace area: Change interval\nSwipe unit area: Toggle steps/cm\nDouble tap: Exit settings");
        }
    }

    // ==================== Feedback Methods ====================

    private void speakFeedback(String text) {
        // Haptic feedback
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(80);
            }
        }

        // TTS feedback
        if (tts != null) {
            tts.stop();
            tts.setSpeechRate(speechRate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }

        // Visual feedback
        ObjectAnimator.ofFloat(tvUnit, "alpha", 0.7f, 1f).setDuration(200).start();
    }

    // ==================== Exit and Save Methods ====================

    private void closeSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("speechRate", speechRate);
        editor.putInt("langIndex", currentLangIndex);
        editor.putInt("pace", paceSeconds);
        editor.putBoolean("useCm", useCm);
        editor.apply();

        // Speak exit message
        boolean isZh = currentLangIndex == 0;
        String exitMessage;
        if (isZh) {
            exitMessage = "设置已保存。语速" + String.format("%.1f", speechRate) +
                    "倍，间隔" + paceSeconds + "秒，单位" + (useCm ? "厘米" : "步数");
        } else {
            exitMessage = "Settings saved. Speed " + String.format("%.1f", speechRate) +
                    "x, interval " + paceSeconds + " seconds, unit " + (useCm ? "cm" : "steps");
        }

        if (tts != null) {
            tts.speak(exitMessage, TextToSpeech.QUEUE_FLUSH, null, null);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        settingsOverlay.setVisibility(View.GONE);
        Toast.makeText(this, exitMessage, Toast.LENGTH_SHORT).show();
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