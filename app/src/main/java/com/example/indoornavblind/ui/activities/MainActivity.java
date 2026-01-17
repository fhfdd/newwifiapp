package com.example.indoornavblind.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.example.indoornavblind.R;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.OfflineTTSService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService;
import com.example.indoornavblind.service.OfflineVoiceAssistantService;
import com.example.indoornavblind.service.PathStorageService;
import com.example.indoornavblind.service.impl.CompassEnhancedNavigationService;
import com.example.indoornavblind.service.impl.L_KnnLocationService;
import com.example.indoornavblind.service.L_WiFiScannerServiceImpl;
import com.example.indoornavblind.util.PathParser;
import com.example.indoornavblind.util.PermissionUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long LONG_PRESS_DURATION = 800;

    private OfflineVoiceAssistantService voiceAssistant;
    private OfflineTTSService voiceService;
    private LocationService locationService;
    private CompassEnhancedNavigationService navigationService;
    private VoskSpeechRecognizerService voskRecognizer;
    private WiFiScannerService wifiScanner;
    private PathStorageService pathStorage;
    private Vibrator vibrator;

    private TextView tvTopDisplay;
    private EditText etVoiceSimulate;
    private Button btnLocateNav, btnVoiceAssistant, btnSettings, btnEmergency;
    private View settingsFullscreen;
    private TextView tvSpeedDisplay, tvLanguageDisplay, tvPaceDisplay;
    // tv_sensor_status 已移除，因为 XML 中不存在

    private Position currentPosition;
    private boolean isInSettingsMode = false;
    private boolean isLocated = false;
    private boolean hasDestination = false;
    private String destinationName = "";
    private float speechSpeed = 1.0f;
    private VoskSpeechRecognizerService.Language currentLanguage = VoskSpeechRecognizerService.Language.CHINESE;
    private int navigationPace = 5000;
    private String lastSpokenText = "";

    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean isLongPressTriggered = false;

    private Handler statusUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdateRunnable;

    private GestureDetector gestureDetector;
    private static final float SPEED_STEP = 0.1f;
    private static final float SPEED_MIN = 0.5f;
    private static final float SPEED_MAX = 2.0f;
    private static final int[] PACE_OPTIONS = {2000, 3000, 5000, 8000};
    private int paceIndex = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);

        initServices();
        initViews();
        initListeners();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            speak("欢迎使用完全离线盲人室内导航系统。支持中英粤三语。单击按钮开始定位", speechSpeed);
        }, 1000);
    }

    private void initServices() {
        voiceService = new OfflineTTSService(this);
        voiceService.init(this);

        wifiScanner = new L_WiFiScannerServiceImpl();
        wifiScanner.init(this);
        locationService = new L_KnnLocationService(wifiScanner);
        locationService.init(this);

        navigationService = new CompassEnhancedNavigationService(voiceService, locationService);
        navigationService.initSensors(this);

        navigationService.setPositionUpdateCallback(newPosition -> {
            runOnUiThread(() -> {
                currentPosition = newPosition;
            });
        });

        navigationService.setNavigationEventCallback(new CompassEnhancedNavigationService.NavigationEventCallback() {
            @Override
            public void onNavigationStarted(String from, String to, int totalSteps, double totalDistance, int estimatedSeconds) {
                runOnUiThread(() -> {
                    updateDisplay(String.format("导航开始：%s → %s", from, to));
                    vibrate(200);
                });
            }

            @Override
            public void onStepAnnounced(int stepIndex, int totalSteps, String instruction, String absoluteDirection) {
                runOnUiThread(() -> updateDisplay(String.format("[%d/%d] %s", stepIndex, totalSteps, instruction)));
            }

            @Override public void onTurnWarning(String t, String a, int s) { runOnUiThread(() -> vibrate(100)); }
            @Override public void onProgressUpdate(int c, int r, double d) {}

            @Override
            public void onArrival(String destination, String detailInfo) {
                runOnUiThread(() -> {
                    hasDestination = false;
                    destinationName = "";
                    updateDisplay("已到达：" + destination);
                    vibrate(500);
                    if (pathStorage != null && currentPosition != null) {
                        pathStorage.recordRoute(currentPosition.getLabel(), destination);
                    }
                });
            }

            @Override
            public void onNavigationStopped(boolean reachedDestination) {
                runOnUiThread(() -> { if (!reachedDestination) updateDisplay("导航已停止"); });
            }

            @Override
            public void onOffRoute(double deviationMeters) {
                runOnUiThread(() -> {
                    vibrate(300);
                    speak(String.format("偏离路线%.1f米", deviationMeters), speechSpeed);
                });
            }

            @Override public void onLocationUpdated(Position position) { runOnUiThread(() -> currentPosition = position); }
            @Override public void onDirectionUpdated(float heading, String cardinal) {}
        });

        pathStorage = new PathStorageService(this);
        voskRecognizer = new VoskSpeechRecognizerService();
        voskRecognizer.init(this, null);

        voiceAssistant = new OfflineVoiceAssistantService(this);
        voiceAssistant.init(voiceService, new OfflineVoiceAssistantService.VoiceAssistantCallback() {
            @Override
            public void onNavigateIntent(String destination) {
                destinationName = destination;
                hasDestination = true;
                speak("正在导航到" + destination, speechSpeed);
                startNavigation(destination);
            }

            @Override public void onLocateIntent() { startLocation(); }

            @Override
            public void onQueryLocationIntent() {
                speak(currentPosition != null ? "您当前在" + currentPosition.getLabel() : "当前位置未知，请先定位", speechSpeed);
            }

            @Override
            public void onQueryNearbyIntent() {
                if (currentPosition != null) announceNearbyPOIs(currentPosition);
                else speak("请先定位", speechSpeed);
            }

            @Override
            public void onQueryProgressIntent() {
                speak(navigationService.isNavigating() ? "导航进行中" : "当前没有进行中的导航", speechSpeed);
            }

            @Override
            public void onStartNavigationIntent() {
                if (hasDestination && destinationName != null) startNavigation(destinationName);
                else speak("请先设置目的地", speechSpeed);
            }

            @Override
            public void onStopNavigationIntent() {
                if (navigationService.isNavigating()) {
                    navigationService.stopNavigation();
                    speak("导航已停止", speechSpeed);
                } else speak("当前没有进行中的导航", speechSpeed);
            }

            @Override public void onRepeatIntent() { if (!lastSpokenText.isEmpty()) speak(lastSpokenText, speechSpeed); }
            @Override public void onHelpIntent(String h) { speak("可以说：去某地、我在哪、附近有什么、停止导航、切换语言", speechSpeed); }
            @Override public void onSettingsIntent() { enterSettingsMode(); }
            @Override public void onSpeedUpIntent() { adjustSpeed(SPEED_STEP); }
            @Override public void onSpeedDownIntent() { adjustSpeed(-SPEED_STEP); }
            @Override public void onEmergencyIntent() { speak("紧急求助已触发", speechSpeed); vibrate(1000); }
            @Override public void onUnknownIntent(String r) { speak("未识别的指令", speechSpeed); }

            @Override
            public void onLocationInputIntent(String location, boolean isStart) {
                if (isStart) {
                    Position pos = findPositionByName(location);
                    if (pos != null) {
                        currentPosition = pos;
                        isLocated = true;
                        navigationService.setCurrentPosition(pos);
                        speak("已设置当前位置为" + location, speechSpeed);
                        updateDisplay("当前位置：" + location);
                    } else speak("未找到位置" + location, speechSpeed);
                } else {
                    destinationName = location;
                    hasDestination = true;
                    speak("目的地设置为" + location, speechSpeed);
                }
            }

            @Override public void onLanguageSwitchIntent(VoskSpeechRecognizerService.Language l) { switchToLanguage(l); }
            @Override public void onListeningStarted() { updateDisplay("正在听..."); vibrate(50); }
            @Override public void onListeningStopped() {}
            @Override public void onRecognitionResult(String t) { updateDisplay("识别：" + t); }
            @Override public void onError(String e) { Log.e(TAG, "语音助手错误: " + e); }
        });

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    private Position findPositionByName(String name) {
        List<String> pois = PathParser.getAllPOINames();
        if (pois.contains(name)) {
            Position pos = new Position();
            pos.setLabel(name);
            return pos;
        }
        return null;
    }

    private void initViews() {
        tvTopDisplay = findViewById(R.id.tv_top_display);
        etVoiceSimulate = findViewById(R.id.et_voice_simulate);
        btnLocateNav = findViewById(R.id.btn_locate_nav);
        btnVoiceAssistant = findViewById(R.id.btn_voice_assistant);
        btnSettings = findViewById(R.id.btn_settings);
        btnEmergency = findViewById(R.id.btn_emergency);
        settingsFullscreen = findViewById(R.id.settings_fullscreen);
        tvSpeedDisplay = findViewById(R.id.tv_speed_display);
        tvLanguageDisplay = findViewById(R.id.tv_language_display);
        tvPaceDisplay = findViewById(R.id.tv_pace_display);
    }

    private void initListeners() {
        tvTopDisplay.setOnClickListener(v -> {
            if (!lastSpokenText.isEmpty()) { speak(lastSpokenText, speechSpeed); vibrate(50); }
        });

        setupLocateNavButton();

        btnVoiceAssistant.setOnClickListener(v -> {
            if (isInSettingsMode) { speak("请先退出设置模式", speechSpeed); return; }
            if (voiceAssistant.isInitialized()) {
                speak("请说出您的指令", speechSpeed);
                vibrate(100);
                new Handler(Looper.getMainLooper()).postDelayed(() -> voiceAssistant.startListening(), 800);
            } else {
                String input = etVoiceSimulate.getText().toString().trim();
                if (!input.isEmpty()) {
                    voiceAssistant.processText(input);
                    etVoiceSimulate.setText("");
                } else speak("语音识别尚未就绪，请在输入框输入指令", speechSpeed);
            }
        });

        btnSettings.setOnClickListener(v -> enterSettingsMode());
        Button btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, R_SettingsActivity.class);
            startActivity(intent);
        });

        btnEmergency.setOnClickListener(v -> {
            speak("紧急求助已发送", speechSpeed);
            vibrate(500);
            if (currentPosition != null) {
                String em = "当前位置：" + currentPosition.getLabel() + "。" + navigationService.getCurrentDirectionInfo();
                new Handler(Looper.getMainLooper()).postDelayed(() -> speak(em, speechSpeed), 1000);
            }
        });

        setupSettingsGestures();
        startStatusUpdater();

        etVoiceSimulate.setOnEditorActionListener((v, actionId, event) -> {
            String input = etVoiceSimulate.getText().toString().trim();
            if (!input.isEmpty()) {
                if (input.startsWith("我在") || input.toLowerCase().startsWith("i am at")) {
                    voiceAssistant.processText(input);
                } else {
                    destinationName = input;
                    hasDestination = true;
                    updateDisplay("目的地：" + destinationName);
                    if (isLocated) speak("目的地已设置为" + destinationName + "，点击开始导航", speechSpeed);
                    else { speak("目的地已设置为" + destinationName + "，正在定位", speechSpeed); startLocation(); }
                }
                etVoiceSimulate.setText("");
            }
            return true;
        });
    }

    private void setupLocateNavButton() {
        btnLocateNav.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressTriggered = false;
                    longPressRunnable = () -> { isLongPressTriggered = true; onLongPressDetected(); };
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    if (!isLongPressTriggered) onSingleClickDetected();
                    return true;
            }
            return false;
        });
    }

    private void onSingleClickDetected() {
        if (isInSettingsMode) { speak("请先退出设置模式", speechSpeed); return; }
        vibrate(50);
        if (navigationService.isNavigating()) {
            speak("正在更新位置", speechSpeed);
            updateDisplay("定位更新中...");
            locationService.locate(new LocationService.LocationCallback() {
                @Override
                public void onSuccess(Position position) {
                    runOnUiThread(() -> {
                        currentPosition = position;
                        navigationService.setCurrentPosition(position);
                        speak("当前在" + position.getLabel(), speechSpeed);
                        updateDisplay("导航中 | 当前：" + position.getLabel());
                    });
                }
                @Override public void onFailure(String error) { runOnUiThread(() -> speak("定位失败，继续导航", speechSpeed)); }
            });
        } else if (!hasDestination || currentPosition == null) {
            if (!hasDestination && currentPosition != null) announceCurrentEnvironment();
            else startLocation();
        } else {
            speak("导航开始", speechSpeed);
            startNavigation(destinationName);
        }
    }

    private void onLongPressDetected() {
        vibrate(200);
        if (navigationService.isNavigating()) {
            navigationService.stopNavigation();
            hasDestination = false;
            destinationName = "";
            String msg = "导航已结束" + (currentPosition != null ? "，当前在" + currentPosition.getLabel() : "");
            speak(msg, speechSpeed);
            updateDisplay(msg);
        } else {
            if (currentPosition != null) announceCurrentEnvironment();
            else { speak("正在为您定位", speechSpeed); startLocation(); }
        }
    }

    private void announceCurrentEnvironment() {
        if (currentPosition == null) return;
        String msg = "当前在" + currentPosition.getLabel() + "。" + navigationService.getCurrentDirectionInfo();
        speak(msg, speechSpeed);
        updateDisplay("当前：" + currentPosition.getLabel());
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            announceNearbyPOIs(currentPosition);
            if (!hasDestination) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    List<String> recs = pathStorage.recommendDestinations(currentPosition.getLabel(), 3);
                    speak(!recs.isEmpty() ? "推荐目的地：" + String.join("、", recs) : "请说出目的地", speechSpeed);
                }, 3000);
            }
        }, 2000);
    }

    private void setupSettingsGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (!isInSettingsMode) return false;
                float dX = e2.getX() - e1.getX();
                float dY = e2.getY() - e1.getY();
                if (Math.abs(dX) > Math.abs(dY)) {
                    if (Math.abs(dX) > 100) { switchLanguage(); return true; }
                } else {
                    if (Math.abs(dY) > 100) { adjustSpeed(dY < 0 ? SPEED_STEP : -SPEED_STEP); return true; }
                }
                return false;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { if (isInSettingsMode) { exitSettingsMode(); return true; } return false; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (isInSettingsMode) { switchPace(); return true; } return false; }
        });
        settingsFullscreen.setOnTouchListener((v, event) -> { gestureDetector.onTouchEvent(event); return true; });
    }

    private void startLocation() {
        speak("正在定位", speechSpeed);
        vibrate(100);
        updateDisplay("定位中...");
        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                runOnUiThread(() -> {
                    currentPosition = position;
                    isLocated = true;
                    navigationService.setCurrentPosition(position);
                    speak("定位成功，当前在" + position.getLabel(), speechSpeed);
                    vibrate(200);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        announceNearbyPOIs(position);
                        if (hasDestination) new Handler(Looper.getMainLooper()).postDelayed(() -> speak("目的地" + destinationName + "，点击开始导航", speechSpeed), 2000);
                    }, 2000);
                });
            }
            @Override public void onFailure(String e) { runOnUiThread(() -> { isLocated = false; speak("WiFi定位失败。" + e, speechSpeed); }); }
        });
    }

    private void announceNearbyPOIs(Position position) {
        List<PathEntity> allPaths = PathParser.getAllPaths();
        List<String> nearby = new ArrayList<>();
        for (PathEntity path : allPaths) {
            if (path.getStartLabel_cn().equals(position.getLabel())) nearby.add(path.getEndLabel_cn());
        }
        if (!nearby.isEmpty()) speak("附近有：" + String.join("、", nearby.subList(0, Math.min(3, nearby.size()))), speechSpeed);
    }

    private void startNavigation(String target) {
        if (currentPosition == null) return;
        destinationName = target;
        hasDestination = true;
        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        // 修正 locale 引用
        navigationService.setNavigationConfig(navigationPace, speechSpeed, currentLanguage.locale);
        List<PathEntity> path = navigationService.calculatePath();
        if (path.isEmpty()) { speak("未找到路径", speechSpeed); hasDestination = false; }
        else navigationService.startContinuousNavigation();
    }

    private void startStatusUpdater() {
        statusUpdateRunnable = new Runnable() {
            @Override public void run() { updateNavigationStatus(); statusUpdateHandler.postDelayed(this, 2000); }
        };
        statusUpdateHandler.post(statusUpdateRunnable);
    }

    private void updateNavigationStatus() {
        if (isInSettingsMode) return;
        String status;
        if (navigationService.isNavigating()) status = "导航中 → " + destinationName;
        else if (hasDestination) status = "目的地：" + destinationName + " | 点击开始";
        else status = currentPosition != null ? "当前：" + currentPosition.getLabel() : "点击按钮定位";
        updateDisplay(status);
    }

    private void enterSettingsMode() {
        isInSettingsMode = true;
        settingsFullscreen.setVisibility(View.VISIBLE);
        speak("进入设置。上下滑动调语速，左右滑动切换语言，单击切换间隔，双击退出", speechSpeed);
        updateSettingsDisplay();
    }

    private void exitSettingsMode() {
        isInSettingsMode = false;
        settingsFullscreen.setVisibility(View.GONE);
        speak(String.format("设置完成。语速%.1f倍，%s", speechSpeed, currentLanguage.displayName), speechSpeed);
    }

    private void updateSettingsDisplay() {
        tvSpeedDisplay.setText(String.format("语速：%.1f倍", speechSpeed));
        tvLanguageDisplay.setText("语言：" + currentLanguage.displayName);
        tvPaceDisplay.setText(String.format("间隔：%d秒", navigationPace / 1000));
    }

    private void adjustSpeed(float delta) {
        speechSpeed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speechSpeed + delta));
        voiceService.setSpeed(speechSpeed);
        updateSettingsDisplay();
        speak(String.format("语速%.1f倍", speechSpeed), speechSpeed);
    }

    private void switchLanguage() {
        VoskSpeechRecognizerService.Language[] langs = VoskSpeechRecognizerService.Language.values();
        int idx = 0;
        for (int i = 0; i < langs.length; i++) { if (langs[i] == currentLanguage) { idx = i; break; } }
        switchToLanguage(langs[(idx + 1) % langs.length]);
    }

    private void switchToLanguage(VoskSpeechRecognizerService.Language language) {
        currentLanguage = language;
        voiceService.setLanguage(language.locale);
        if (voiceAssistant != null) voiceAssistant.switchLanguage(language);
        updateSettingsDisplay();
        speak("已切换到" + language.displayName, speechSpeed);
    }

    private void switchPace() {
        paceIndex = (paceIndex + 1) % PACE_OPTIONS.length;
        navigationPace = PACE_OPTIONS[paceIndex];
        updateSettingsDisplay();
        speak(String.format("间隔%d秒", navigationPace / 1000), speechSpeed);
    }

    private void updateDisplay(String text) { if (tvTopDisplay != null) tvTopDisplay.setText(text); }

    private void speak(String text, float speed) {
        lastSpokenText = text;
        if (voiceService != null) voiceService.speak(text, speed);
        updateDisplay(text);
    }

    private void vibrate(long ms) { if (vibrator != null) vibrator.vibrate(ms); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceAssistant != null) voiceAssistant.destroy();
        if (voiceService != null) voiceService.shutdown();
        if (navigationService != null) navigationService.stopNavigation();
        statusUpdateHandler.removeCallbacksAndMessages(null);
    }
}