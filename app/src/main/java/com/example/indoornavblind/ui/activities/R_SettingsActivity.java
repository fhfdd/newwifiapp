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
    private TextView tvSpeed, tvLang, tvPace, tvUnit, tvStride, tvTitle, tvHint;
    private TextToSpeech tts;
    private SharedPreferences prefs;

    private float speechRate = 1.0f;
    private String currentLanguageCode = "zh";
    private int paceSeconds = 3;
    private boolean useCm = false;
    private int strideCm = 70;

    private static final int[] PACE_OPTIONS = {3, 5, 7};
    private static final int STRIDE_MIN_CM = 30;
    private static final int STRIDE_MAX_CM = 120;
    private static final int STRIDE_STEP_CM = 5;

    private GestureDetector mainGestureDetector;
    private GestureDetector paceGestureDetector;
    private GestureDetector unitGestureDetector;
    private GestureDetector strideGestureDetector;

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
        tvStride = findViewById(R.id.tv_stride_display);
        tvTitle = findViewById(R.id.tv_title);
        tvHint = findViewById(R.id.tv_hint);

        prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechRate = prefs.getFloat("speechRate", 1.0f);

        currentLanguageCode = LanguageManager.getLanguage(this);
        if (!currentLanguageCode.equals("zh") && !currentLanguageCode.equals("en")) {
            currentLanguageCode = "zh";
        }

        paceSeconds = prefs.getInt("pace", 3);
        boolean validPace = false;
        for (int p : PACE_OPTIONS) {
            if (p == paceSeconds) {
                validPace = true;
                break;
            }
        }
        if (!validPace) {
            paceSeconds = 3;
        }

        useCm = prefs.getBoolean("useCm", false);
        strideCm = prefs.getInt("strideCm", 70);
        if (strideCm < STRIDE_MIN_CM || strideCm > STRIDE_MAX_CM) {
            strideCm = 70;
        }

        updateDisplay();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                applyTtsLanguage();
                tts.setSpeechRate(speechRate);
                Log.d(TAG, "TTS initialized, language: " + currentLanguageCode + ", rate: " + speechRate);
            }
        });

        mainGestureDetector = new GestureDetector(this, new MainGestureListener());
        settingsOverlay.setOnTouchListener((v, event) -> mainGestureDetector.onTouchEvent(event));

        paceGestureDetector = new GestureDetector(this, new PaceGestureListener());
        tvPace.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            paceGestureDetector.onTouchEvent(event);
            return true;
        });

        unitGestureDetector = new GestureDetector(this, new UnitGestureListener());
        tvUnit.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            unitGestureDetector.onTouchEvent(event);
            return true;
        });

        strideGestureDetector = new GestureDetector(this, new StrideGestureListener());
        tvStride.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            strideGestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void applyTtsLanguage() {
        if (tts == null) return;

        Locale locale = currentLanguageCode.equals("en") ? Locale.US : Locale.SIMPLIFIED_CHINESE;
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language not supported: " + currentLanguageCode);
            tts.setLanguage(Locale.getDefault());
        } else {
            Log.d(TAG, "TTS language set to: " + currentLanguageCode);
        }
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
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onSwipeRight();
                    } else {
                        onSwipeLeft();
                    }
                    return true;
                }
            } else {
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

    private class StrideGestureListener extends GestureDetector.SimpleOnGestureListener {
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

            float diffY = e2.getY() - e1.getY();
            if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffY > 0) {
                    decreaseStride();
                } else {
                    increaseStride();
                }
                return true;
            }
            return false;
        }
    }

    private void onSwipeUp() {
        speechRate = Math.min(3.0f, speechRate + 0.1f);
        updateDisplay();
        if (tts != null) {
            tts.setSpeechRate(speechRate);
        }
        String feedback = currentLanguageCode.equals("zh")
                ? String.format(Locale.CHINA, "语速已调整为：%.1f倍", speechRate)
                : String.format(Locale.US, "Speech rate adjusted to %.1fx", speechRate);
        speakFeedback(feedback);
    }

    private void onSwipeDown() {
        speechRate = Math.max(0.5f, speechRate - 0.1f);
        updateDisplay();
        if (tts != null) {
            tts.setSpeechRate(speechRate);
        }
        String feedback = currentLanguageCode.equals("zh")
                ? String.format(Locale.CHINA, "语速已调整为：%.1f倍", speechRate)
                : String.format(Locale.US, "Speech rate adjusted to %.1fx", speechRate);
        speakFeedback(feedback);
    }

    private void onSwipeLeft() {
        toggleLanguage();
    }

    private void onSwipeRight() {
        toggleLanguage();
    }

    private void toggleLanguage() {
        String newLanguageCode = currentLanguageCode.equals("zh") ? "en" : "zh";
        LanguageManager.setLanguage(this, newLanguageCode);
        currentLanguageCode = newLanguageCode;
        applyTtsLanguage();
        updateDisplay();

        String feedback = currentLanguageCode.equals("zh")
                ? "语言已切换为中文"
                : "Language switched to English";
        speakFeedback(feedback);
    }

    private void onPaceSwipeLeft() {
        int currentIndex = getPaceIndex();
        int newIndex = (currentIndex + 1) % PACE_OPTIONS.length;
        paceSeconds = PACE_OPTIONS[newIndex];
        updateDisplay();

        String feedback = currentLanguageCode.equals("zh")
                ? String.format(Locale.CHINA, "间隔已调整为%d秒", paceSeconds)
                : String.format(Locale.US, "Interval adjusted to %d seconds", paceSeconds);
        speakFeedback(feedback);
    }

    private void onPaceSwipeRight() {
        int currentIndex = getPaceIndex();
        int newIndex = (currentIndex - 1 + PACE_OPTIONS.length) % PACE_OPTIONS.length;
        paceSeconds = PACE_OPTIONS[newIndex];
        updateDisplay();

        String feedback = currentLanguageCode.equals("zh")
                ? String.format(Locale.CHINA, "间隔已调整为%d秒", paceSeconds)
                : String.format(Locale.US, "Interval adjusted to %d seconds", paceSeconds);
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

    private void switchDistanceUnit() {
        useCm = !useCm;
        updateDisplay();

        String feedback = currentLanguageCode.equals("zh")
                ? "距离单位已切换为" + (useCm ? "厘米" : "步数")
                : "Distance unit switched to " + (useCm ? "cm" : "steps");
        speakFeedback(feedback);
    }

    private void increaseStride() {
        strideCm = Math.min(STRIDE_MAX_CM, strideCm + STRIDE_STEP_CM);
        updateDisplay();

        String feedback = currentLanguageCode.equals("zh")
                ? "步幅已调整为" + strideCm + "厘米"
                : "Stride length set to " + strideCm + " centimeters";
        speakFeedback(feedback);
    }

    private void decreaseStride() {
        strideCm = Math.max(STRIDE_MIN_CM, strideCm - STRIDE_STEP_CM);
        updateDisplay();

        String feedback = currentLanguageCode.equals("zh")
                ? "步幅已调整为" + strideCm + "厘米"
                : "Stride length set to " + strideCm + " centimeters";
        speakFeedback(feedback);
    }

    private void updateDisplay() {
        boolean isZh = currentLanguageCode.equals("zh");

        tvTitle.setText(R.string.settings_mode);

        if (isZh) {
            tvSpeed.setText(String.format(Locale.CHINA, "语速：%.1f倍", speechRate));
            tvLang.setText("语言：中文");
            tvPace.setText(String.format(Locale.CHINA, "间隔：%d秒", paceSeconds));
            tvUnit.setText("距离单位：" + (useCm ? "厘米" : "步数"));
            tvStride.setText("步幅：" + strideCm + "厘米");
            tvHint.setText("上下滑动：调节语速\n左右滑动：切换语言\n左右滑动间隔区域：切换间隔\n左右滑动单位区域：切换步/厘米\n上下滑动步幅区域：调节步幅\n双击：退出设置");
        } else {
            tvSpeed.setText(String.format(Locale.US, "Speed: %.1fx", speechRate));
            tvLang.setText("Language: English");
            tvPace.setText(String.format(Locale.US, "Interval: %d sec", paceSeconds));
            tvUnit.setText("Distance unit: " + (useCm ? "cm" : "steps"));
            tvStride.setText("Stride: " + strideCm + " cm");
            tvHint.setText("Swipe up/down: Adjust speed\nSwipe left/right: Change language\nSwipe pace area: Change interval\nSwipe unit area: Toggle steps/cm\nSwipe stride area up/down: Adjust stride\nDouble tap: Exit settings");
        }

        Log.d(TAG, "Display updated - language: " + currentLanguageCode
                + ", useCm: " + useCm
                + ", strideCm: " + strideCm);
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
            tts.setSpeechRate(speechRate);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }

        ObjectAnimator.ofFloat(tvStride, "alpha", 0.7f, 1f).setDuration(200).start();
    }

    private void closeSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("speechRate", speechRate);
        editor.putInt("pace", paceSeconds);
        editor.putBoolean("useCm", useCm);
        editor.putInt("strideCm", strideCm);
        editor.apply();

        String exitMessage;
        if (currentLanguageCode.equals("zh")) {
            exitMessage = String.format(Locale.CHINA,
                    "设置已保存。语速%.1f倍，间隔%d秒，单位%s，步幅%d厘米",
                    speechRate, paceSeconds, useCm ? "厘米" : "步数", strideCm);
        } else {
            exitMessage = String.format(Locale.US,
                    "Settings saved. Speed %.1fx, interval %d seconds, unit %s, stride %d cm",
                    speechRate, paceSeconds, useCm ? "cm" : "steps", strideCm);
        }

        if (tts != null) {
            tts.speak(exitMessage, TextToSpeech.QUEUE_FLUSH, null, null);
        }

        setResult(RESULT_OK);
        settingsOverlay.setVisibility(android.view.View.GONE);
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
