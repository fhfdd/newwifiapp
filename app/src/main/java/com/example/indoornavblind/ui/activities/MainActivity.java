package com.example.indoornavblind.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.example.indoornavblind.R;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService;
import com.example.indoornavblind.service.impl.CompassEnhancedNavigationService;
import com.example.indoornavblind.service.impl.L_KnnLocationService;
import com.example.indoornavblind.service.L_WiFiScannerServiceImpl;
import com.example.indoornavblind.service.impl.LocalIntentEngine;
import com.example.indoornavblind.util.LanguageManager;
import com.example.indoornavblind.util.NavigationDataInitializer;
import com.example.indoornavblind.util.PathParser;
import com.example.indoornavblind.util.PermissionUtil;
import com.example.indoornavblind.factory.ServiceFactory;
import com.example.indoornavblind.service.C_TextToSpeechService;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final int SETTINGS_REQUEST_CODE = 1001;
    private static final long LONG_PRESS_DURATION = 800;

    private C_TextToSpeechService ttsService;
    private VoskSpeechRecognizerService voskService;
    private CompassEnhancedNavigationService navigationService;
    private LocationService locationService;
    private LocalIntentEngine intentEngine;
    private Vibrator vibrator;

    private TextView tvTopDisplay, tvUnitDisplay;
    private EditText etVoiceSimulate;
    private Button btnLocateNav, btnVoiceAssistant, btnSettings, btnEmergency;

    private Position currentPosition;
    private boolean hasDestination = false;
    private String destinationName = "";
    private float speechSpeed = 1.0f;

    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean isLongPressTriggered = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.updateBaseContextLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load Settings
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechSpeed = prefs.getFloat("speechRate", 1.0f);

        setContentView(R.layout.activity_main);
        initViews();
        updateUnitDisplay();

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);
        initServices();
        initListeners();

        new Thread(() -> {
            NavigationNodeDao dao = AppDatabase.getInstance().navigationNodeDao();
            if (dao.getAllNodes().isEmpty()) {
                NavigationDataInitializer.initializeSampleData(dao);
            }
        }).start();
    }

    private void initViews() {
        tvTopDisplay = findViewById(R.id.tv_top_display);
        tvUnitDisplay = findViewById(R.id.tv_unit_display);
        etVoiceSimulate = findViewById(R.id.et_voice_simulate);
        btnLocateNav = findViewById(R.id.btn_locate_nav);
        btnVoiceAssistant = findViewById(R.id.btn_voice_assistant);
        btnSettings = findViewById(R.id.btn_settings);
        btnEmergency = findViewById(R.id.btn_emergency);
    }

    private void updateUnitDisplay() {
        if (tvUnitDisplay == null) return;
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        boolean useCm = prefs.getBoolean("useCm", false);

        // FIX: Pass the parameter to fill the %s in XML
        String unitLabel = useCm ? getString(R.string.unit_cm) : getString(R.string.unit_steps);
        tvUnitDisplay.setText(getString(R.string.distance_unit_format, unitLabel));
    }

    private void initServices() {
        ServiceFactory factory = ServiceFactory.getInstance(this);
        ttsService = factory.getTtsService();
        voskService = factory.getVoskService();
        intentEngine = new LocalIntentEngine(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        L_WiFiScannerServiceImpl wifiScanner = new L_WiFiScannerServiceImpl();
        wifiScanner.init(this);
        locationService = new L_KnnLocationService(wifiScanner);
        locationService.init(this);

        navigationService = new CompassEnhancedNavigationService(ttsService, locationService);
        navigationService.initSensors(this);

        navigationService.setNavigationEventCallback(new CompassEnhancedNavigationService.NavigationEventCallback() {
            @Override public void onNavigationStarted(String f, String t, int ts, double td, int es) {
                runOnUiThread(() -> tvTopDisplay.setText(getString(R.string.navigating)));
            }
            @Override public void onArrival(String dest, String info) {
                runOnUiThread(() -> {
                    speak(getString(R.string.settings_saved), speechSpeed);
                    hasDestination = false;
                });
            }
            @Override public void onOffRoute(double dev) {
                runOnUiThread(() -> speak(getString(R.string.command_not_recognized), speechSpeed));
            }
            @Override public void onStepAnnounced(int i, int t, String ins, String d) {}
            @Override public void onTurnWarning(String t, String a, int s) { vibrate(100); }
            @Override public void onProgressUpdate(int c, int r, double d) {}
            @Override public void onNavigationStopped(boolean r) {}
            @Override public void onLocationUpdated(Position p) { currentPosition = p; }
            @Override public void onDirectionUpdated(float h, String c) {}
        });
    }

    private void initListeners() {
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, R_SettingsActivity.class);
            startActivityForResult(intent, SETTINGS_REQUEST_CODE);
        });

        btnEmergency.setOnClickListener(v -> {
            speak(getString(R.string.emergency_assistance), speechSpeed);
            vibrate(500);
        });

        btnVoiceAssistant.setOnClickListener(v -> {
            if (voskService != null && voskService.isInitialized()) {
                voskService.startListening();
                tvTopDisplay.setText(getString(R.string.voice_assistant));
            }
        });

        setupLocateNavButton();
    }

    private void setupLocateNavButton() {
        btnLocateNav.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                isLongPressTriggered = false;
                longPressRunnable = () -> { isLongPressTriggered = true; onLongPress(); };
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                longPressHandler.removeCallbacks(longPressRunnable);
                if (!isLongPressTriggered) onSingleClick();
            }
            return true;
        });
    }

    private void onSingleClick() {
        vibrate(50);
        speak(getString(R.string.locating), speechSpeed);
        locationService.locate(new LocationService.LocationCallback() {
            @Override public void onSuccess(Position p) {
                runOnUiThread(() -> {
                    currentPosition = p;
                    speak(getString(R.string.current_location) + ": " + p.getLabel(), speechSpeed);
                });
            }
            @Override public void onFailure(String e) {
                runOnUiThread(() -> speak(getString(R.string.command_not_recognized), speechSpeed));
            }
        });
    }

    private void onLongPress() {
        vibrate(200);
        if (hasDestination && currentPosition != null) {
            navigationService.setTarget(destinationName);
            navigationService.startContinuousNavigation();
        } else {
            speak(getString(R.string.locate_nav_desc), speechSpeed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SETTINGS_REQUEST_CODE) {
            recreate(); // Refresh language and unit display
        }
    }

    private void speak(String text, float speed) {
        if (ttsService != null && ttsService.isReady()) ttsService.speak(text, speed);
    }

    private void vibrate(long ms) {
        if (vibrator != null) vibrator.vibrate(ms);
    }
}