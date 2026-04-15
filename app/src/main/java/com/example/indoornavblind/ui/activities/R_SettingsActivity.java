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
    private SharedPreferences prefs;

    // Settings values
    private float speechRate = 1.0f;
    private String currentLanguageCode = "zh"; // "zh" or "en"
    private int paceSeconds = 3; // 3, 5, or 7 seconds
    private boolean useCm = false; // false = steps, true = cm

    // Pace cycle options
    private static final int[] PACE_OPTIONS = {3, 5, 7};

    // Gesture detectors
    private GestureDetector mainGestureDetector;
    private GestureDetector paceGestureDetector;
    private GestureDetector unitGestureDetector;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.updateBaseContextLocale(newBase));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        settingsOverlay = findViewById(R.id.settings_fullscreen);
        tvSpeed = findViewById(R.id.tv_speed_display);
        tvLang = findViewById(R.id.tv_language_display);
        tvPace = findViewById(R.id.tv_pace_display);
        tvUnit = findViewById(R.id.tv_unit_display);
        tvTitle = findViewById(R.id.tv_title);
        tvHint = findViewById(R.id.tv_hint);

        // Load saved preferences
        prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechRate = prefs.getFloat("speechRate", 1.0f);

        // Load language from LanguageManager (single source of truth)
        currentLanguageCode = LanguageManager.getLanguage(this);
        if (!currentLanguageCode.equals("zh") && !currentLanguageCode.equals("en")) {
            currentLanguageCode = "zh"; // default to Chinese
        }

        paceSeconds = prefs.getInt("pace", 3);
        // Ensure paceSeconds is one of the valid options
        boolean validPace = false;
        for (int p : PACE_OPTIONS) {
            if (p == paceSeconds) validPace = true;
        }
        if (!validPace) paceSeconds = 3;

        useCm = prefs.getBoolean("useCm", false); // false = steps, true = cm

        // Update display
        updateDisplay();

        // Initialize TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                applyTtsLanguage();
                tts.setSpeechRate(speechRate);
                Log.d(TAG, "TTS initialized, language: " + currentLanguageCode + ", rate: " + speechRate);
            }
        });

        // Main gesture detector for speed (up/down) and language (left/right) anywhere
        mainGestureDetector = new GestureDetector(this, new MainGestureListener());
        settingsOverlay.setOnTouchListener((v, event) -> mainGestureDetector.onTouchEvent(event));

        // Pace gesture detector (left/right swipe on pace area)
        paceGestureDetector = new GestureDetector(this, new PaceGestureListener());
        tvPace.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            paceGestureDetector.onTouchEvent(event);
            return true;
        });

        // Unit gesture detector (left/right swipe on unit area)
        unitGestureDetector = new GestureDetector(this, new UnitGestureListener());
        tvUnit.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            unitGestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void applyTtsLanguage() {
        if (tts == null) return;

        Locale locale;
        if (currentLanguageCode.equals("en")) {
            locale = Locale.US;
        } else {
            locale = Locale.SIMPLIFIED_CHINESE;
        }

        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language not supported: " + currentLanguageCode);
            // Fallback to default
            tts.setLanguage(Locale.getDefault());
        } else {
            Log.d(TAG, "TTS language set to: " + currentLanguageCode);
        }
    }

    /**
     * Main gesture listener for speed (vertical swipe) and language (horizontal swipe)
     */
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

            // Check if horizontal or vertical swipe
            if (Math.abs(diffX) > Math.abs(diffY)) {
                // Horizontal swipe - change language
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onSwipeRight();  // Right swipe
                    } else {
                        onSwipeLeft();   // Left swipe
                    }
                    return true;
                }
            } else {
                // Vertical swipe - change speech rate
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        onSwipeDown();   // Down swipe (decrease speed)
                    } else {
                        onSwipeUp();     // Up swipe (increase speed)
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

    /**
     * Pace gesture listener - left/right swipe to change pace interval
     */
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
                // Left or right swipe on pace area
                if (diffX > 0) {
                    onPaceSwipeRight();
                } else {
                    onPaceSwipeLeft();
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Unit gesture listener - left/right swipe to change distance unit (steps/cm)
     */
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
                // Left or right swipe on unit area - toggle unit
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
        String feedback = String.format(Locale.US, "语速已调整为：%.1f倍", speechRate);
        speakFeedback(feedback);
    }

    private void onSwipeDown() {
        speechRate = Math.max(0.5f, speechRate - 0.1f);
        updateDisplay();
        if (tts != null) {
            tts.setSpeechRate(speechRate);
        }
        String feedback = String.format(Locale.US, "语速已调整为：%.1f倍", speechRate);
        speakFeedback(feedback);
    }

    // ==================== Language Methods ====================

    private void onSwipeLeft() {
        toggleLanguage();
    }

    private void onSwipeRight() {
        toggleLanguage();
    }

    private void toggleLanguage() {
        // Toggle between "zh" and "en"
        String newLanguageCode = currentLanguageCode.equals("zh") ? "en" : "zh";

        // Save to LanguageManager (persists across app)
        LanguageManager.setLanguage(this, newLanguageCode);

        // Update current language code
        currentLanguageCode = newLanguageCode;

        // Update TTS language immediately (no recreate needed)
        applyTtsLanguage();

        // Update display with new language
        updateDisplay();

        // Speak feedback in the new language
        String feedback = currentLanguageCode.equals("zh")
                ? "语言已切换为中文"
                : "Language switched to English";
        speakFeedback(feedback);
    }

    // ==================== Pace Methods ====================

    private void onPaceSwipeLeft() {
        // Cycle to next pace value
        int currentIndex = getPaceIndex();
        int newIndex = (currentIndex + 1) % PACE_OPTIONS.length;
        paceSeconds = PACE_OPTIONS[newIndex];
        updateDisplay();

        String feedback = String.format(Locale.US, "间隔已调整为%d秒", paceSeconds);
        speakFeedback(feedback);
    }

    private void onPaceSwipeRight() {
        // Cycle to previous pace value
        int currentIndex = getPaceIndex();
        int newIndex = (currentIndex - 1 + PACE_OPTIONS.length) % PACE_OPTIONS.length;
        paceSeconds = PACE_OPTIONS[newIndex];
        updateDisplay();

        String feedback = String.format(Locale.US, "间隔已调整为%d秒", paceSeconds);
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
        updateDisplay();

        String unitName = useCm ? "厘米" : "步数";
        String feedback = "距离单位已切换为" + unitName;
        speakFeedback(feedback);
    }

    // ==================== Display Update Methods ====================

    private void updateDisplay() {
        boolean isZh = currentLanguageCode.equals("zh");

        // Update title
        tvTitle.setText(R.string.settings_mode);

        // Update speech rate display
        tvSpeed.setText(String.format(Locale.US, "语速：%.1f倍", speechRate));

        // Update language display
        String langDisplayName = isZh ? "中文" : "English";
        tvLang.setText(String.format(Locale.US, "语言：%s", langDisplayName));

        // Update pace display
        tvPace.setText(String.format(Locale.US, "间隔：%d秒", paceSeconds));

        // Update unit display - direct string concatenation
        String unitName = useCm ? "厘米" : "步数";
        String unitDisplayText = "距离单位：" + unitName;
        tvUnit.setText(unitDisplayText);

        // Update hint text based on language
        if (isZh) {
            tvHint.setText("上下滑动：调节语速\n左右滑动：切换语言\n左右滑动间隔区域：切换间隔\n左右滑动单位区域：切换步/厘米\n双击：退出设置");
        } else {
            tvHint.setText("Swipe up/down: Adjust speed\nSwipe left/right: Change language\nSwipe pace area: Change interval\nSwipe unit area: Toggle steps/cm\nDouble tap: Exit settings");
        }

        Log.d(TAG, "Display updated - language: " + currentLanguageCode + ", useCm: " + useCm + ", unit text: " + unitDisplayText);
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

        // Visual feedback animation on unit display
        ObjectAnimator.ofFloat(tvUnit, "alpha", 0.7f, 1f).setDuration(200).start();
    }

    // ==================== Exit and Save Methods ====================

    private void closeSettings() {
        // Save all settings to SharedPreferences
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("speechRate", speechRate);
        editor.putInt("pace", paceSeconds);
        editor.putBoolean("useCm", useCm);
        editor.apply();

        // Announce exit with summary of changes
        String exitMessage;
        if (currentLanguageCode.equals("zh")) {
            exitMessage = String.format(Locale.CHINA, "设置已保存。语速%.1f倍，间隔%d秒，单位%s",
                    speechRate, paceSeconds, useCm ? "厘米" : "步数");
        } else {
            exitMessage = String.format(Locale.US, "Settings saved. Speed %.1fx, interval %d seconds, unit %s",
                    speechRate, paceSeconds, useCm ? "cm" : "steps");
        }

        if (tts != null) {
            tts.speak(exitMessage, TextToSpeech.QUEUE_FLUSH, null, null);
            // Give TTS time to speak before finishing
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        // Set result to indicate settings were changed
        setResult(RESULT_OK);

        // Hide overlay and finish
        settingsOverlay.setVisibility(View.GONE);
        Toast.makeText(this, exitMessage, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onBackPressed() {
        closeSettings();
        super.onBackPressed();

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