package com.example.indoornavblind.ui.activities;




import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.example.indoornavblind.R;

import com.example.indoornavblind.service.C_TextToSpeechService;
import com.example.indoornavblind.service.VoiceService;

import java.util.Locale;

public class R_SettingsActivity extends AppCompatActivity {
    private SeekBar speechSpeedBar;
    private EditText stepLengthInput, contactInput;
    private CheckBox vibrationEnable, voiceEnable;
    private Button saveButton;
    private TextToSpeech tts;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        speechSpeedBar = findViewById(R.id.speechSpeedBar);
        stepLengthInput = findViewById(R.id.stepLengthInput);
        contactInput = findViewById(R.id.contactInput);
        vibrationEnable = findViewById(R.id.vibrationEnable);
        voiceEnable = findViewById(R.id.voiceEnable);
        saveButton = findViewById(R.id.saveButton);

        prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);

        // Initialize values
        speechSpeedBar.setProgress(prefs.getInt("speechSpeed", 100));
        stepLengthInput.setText(prefs.getString("stepLength", "0.75"));
        contactInput.setText(prefs.getString("contact", ""));
        vibrationEnable.setChecked(prefs.getBoolean("vibration", true));
        voiceEnable.setChecked(prefs.getBoolean("voice", true));

        // Setup Text-to-Speech
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.ENGLISH);
            }
        });

        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("speechSpeed", speechSpeedBar.getProgress());
        editor.putString("stepLength", stepLengthInput.getText().toString());
        editor.putString("contact", contactInput.getText().toString());
        editor.putBoolean("vibration", vibrationEnable.isChecked());
        editor.putBoolean("voice", voiceEnable.isChecked());
        editor.apply();

        Toast.makeText(this, "Settings saved successfully.", Toast.LENGTH_SHORT).show();

        // Optional voice feedback
        if (voiceEnable.isChecked()) {
            float speed = speechSpeedBar.getProgress() / 100f;
            tts.setSpeechRate(speed);
            tts.speak("Settings updated successfully.", TextToSpeech.QUEUE_FLUSH, null, null);
        }

        // Optional vibration feedback
        if (vibrationEnable.isChecked()) {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.os.VibrationEffect effect =
                            android.os.VibrationEffect.createOneShot(200,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(200); // For older Android versions
                }
            }
        }
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