package com.example.indoornavblind.ui.activities;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.indoornavblind.R;
import com.example.indoornavblind.util.LanguageManager;
import java.util.Locale;

public class R_SettingsActivity extends AppCompatActivity {
    private FrameLayout settingsOverlay;
    private TextView tvSpeed, tvLang, tvPace, tvUnit;
    private TextToSpeech tts;
    private SharedPreferences prefs;

    private float speechRate;
    private String currentLang;
    private int paceSeconds;
    private boolean useCm;

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
        settingsOverlay.setVisibility(View.VISIBLE);

        tvSpeed = findViewById(R.id.tv_speed_display);
        tvLang = findViewById(R.id.tv_language_display);
        tvPace = findViewById(R.id.tv_pace_display);
        tvUnit = findViewById(R.id.tv_unit_display);

        prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechRate = prefs.getFloat("speechRate", 1.0f);
        currentLang = LanguageManager.getLanguage(this);
        paceSeconds = prefs.getInt("pace", 3);
        useCm = prefs.getBoolean("useCm", false);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(currentLang.equals("en") ? Locale.US : Locale.CHINESE);
            }
        });

        updateUI();

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { saveAndExit(); return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (Math.abs(e2.getX() - e1.getX()) > 100) toggleLanguage();
                else if (e1.getY() - e2.getY() > 100) adjustSpeed(0.1f);
                else if (e2.getY() - e1.getY() > 100) adjustSpeed(-0.1f);
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleUnit(); return true; }
        });

        settingsOverlay.setOnTouchListener((v, event) -> gd.onTouchEvent(event));
    }

    private void toggleLanguage() {
        currentLang = currentLang.equals("zh") ? "en" : "zh";
        LanguageManager.setLanguage(this, currentLang);
        prefs.edit().putString("language", currentLang).apply();
        recreate();
    }

    private void adjustSpeed(float delta) {
        speechRate = Math.max(0.5f, Math.min(2.0f, speechRate + delta));
        updateUI();
        tts.setSpeechRate(speechRate);
        tts.speak(getString(R.string.speed_adjusted, speechRate), TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void toggleUnit() {
        useCm = !useCm;
        updateUI();
        String unitName = useCm ? getString(R.string.unit_cm) : getString(R.string.unit_steps);
        tts.speak(getString(R.string.distance_unit_changed, unitName), TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void updateUI() {
        // FIX: All these getString calls now pass the required parameters (%f, %s, %d)
        tvSpeed.setText(getString(R.string.speech_rate_format, speechRate));

        String langName = currentLang.equals("zh") ? "中文" : "English";
        tvLang.setText(getString(R.string.language_format, langName));

        tvPace.setText(getString(R.string.pace_format, paceSeconds));

        String unitName = useCm ? getString(R.string.unit_cm) : getString(R.string.unit_steps);
        tvUnit.setText(getString(R.string.distance_unit_format, unitName));
    }

    private void saveAndExit() {
        prefs.edit()
                .putFloat("speechRate", speechRate)
                .putInt("pace", paceSeconds)
                .putBoolean("useCm", useCm)
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }
}