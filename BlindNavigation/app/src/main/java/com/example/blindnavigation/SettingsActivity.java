package com.example.blindnavigation;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "UserSettings";
    private TextToSpeech tts;
    private GestureDetector gestureDetector;
    private float speechRate = 1.0f;
    private String selectedLanguage = "English";
    private String navigationUnit = "Meters";

    private SeekBar speedSeekBar;
    private TextView speedValue;
    private Spinner languageSpinner;
    private RadioGroup unitGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // --- Initialize UI elements
        speedSeekBar = findViewById(R.id.speechSpeedSeekBar);
        speedValue = findViewById(R.id.speechSpeedValue);
        languageSpinner = findViewById(R.id.languageSpinner);
        unitGroup = findViewById(R.id.navigationUnitGroup);

        // --- Load saved settings
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        speechRate = prefs.getFloat("speechRate", 1.0f);
        selectedLanguage = prefs.getString("language", "English");
        navigationUnit = prefs.getString("unit", "Meters");

        // --- Setup SeekBar
        speedSeekBar.setProgress((int) ((speechRate - 0.5f) * 100));
        speedValue.setText(String.format(Locale.getDefault(), "%.1f×", speechRate));

        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speechRate = 0.5f + (progress / 100f);  // Range: 0.5x - 1.5x
                speedValue.setText(String.format(Locale.getDefault(), "%.1f×", speechRate));
                speak("Speech speed " + String.format("%.1f times", speechRate));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // --- Setup Language Spinner
        languageSpinner.setSelection(getLanguageIndex(selectedLanguage));
        languageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                selectedLanguage = parent.getItemAtPosition(position).toString();
                setAppLanguage(selectedLanguage);
                speak("Language set to " + selectedLanguage);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        // --- Setup Radio Buttons (Navigation Unit)
        RadioButton unitMeters = findViewById(R.id.unitMeters);
        RadioButton unitSteps = findViewById(R.id.unitSteps);
        if (navigationUnit.equals("Meters")) unitMeters.setChecked(true);
        else unitSteps.setChecked(true);

        unitGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.unitMeters) navigationUnit = "Meters";
            else navigationUnit = "Steps";
            speak("Navigation unit set to " + navigationUnit);
        });

        // --- Gesture (optional swipe for save/cancel)
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        savePreferences();
                        speak("Settings saved");
                    } else {
                        speak("Cancelled changes");
                        finish();
                    }
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.gestureArea).setOnTouchListener((v, event) ->
                gestureDetector.onTouchEvent(event));

        // --- Initialize TTS
        initTTS();
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                setAppLanguage(selectedLanguage);
                tts.setSpeechRate(speechRate);
            } else {
                Toast.makeText(this, "TTS Initialization failed.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setAppLanguage(String selectedLang) {
        Locale locale;
        switch (selectedLang) {
            case "中文 (Mandarin)":
                locale = Locale.SIMPLIFIED_CHINESE;
                break;
            case "廣東話 (Cantonese)":
                locale = new Locale("zh", "HK");
                break;
            default:
                locale = Locale.ENGLISH;
        }

        int res = tts.setLanguage(locale);
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH);
        }
    }

    private int getLanguageIndex(String lang) {
        switch (lang) {
            case "中文 (Mandarin)": return 1;
            case "廣東話 (Cantonese)": return 2;
            default: return 0;
        }
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID");
        }
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putFloat("speechRate", speechRate);
        editor.putString("language", selectedLanguage);
        editor.putString("unit", navigationUnit);
        editor.apply();
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