package com.example.indoornavblind.ui.activities;

import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.example.indoornavblind.R;
import com.example.indoornavblind.service.TextToSpeechService;
import com.example.indoornavblind.service.VoiceService;

public class SettingsActivity extends AppCompatActivity {
    private VoiceService voiceService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        voiceService = new TextToSpeechService();
        voiceService.init(this);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        voiceService.shutdown();
    }
}