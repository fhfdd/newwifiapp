package com.example.indoornavblind.ui.activities;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.indoornavblind.service.C_TextToSpeechService;
import com.example.indoornavblind.service.VoiceService;

public class SettingsActivity extends AppCompatActivity {
    private VoiceService voiceService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        voiceService = new C_TextToSpeechService(this);
        voiceService.init(this);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        voiceService.shutdown();
    }
}