package com.example.indoornavblind.ui.activities;

import android.content.Context;
import android.content.SharedPreferences;
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
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.database.entity.NavigationNodeEntity;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService;
import com.example.indoornavblind.service.PathStorageService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long LONG_PRESS_DURATION = 800;

    private ServiceFactory serviceFactory;
    private C_TextToSpeechService ttsService;
    private VoskSpeechRecognizerService voskService;
    private CompassEnhancedNavigationService navigationService;
    private LocationService locationService;
    private L_WiFiScannerServiceImpl wifiScanner;
    private PathStorageService pathStorage;
    private Vibrator vibrator;

    private TextView tvTopDisplay;
    private EditText etVoiceSimulate;
    private Button btnLocateNav, btnVoiceAssistant, btnSettings, btnEmergency;
    private View settingsFullscreen;
    private TextView tvSpeedDisplay, tvLanguageDisplay, tvPaceDisplay;

    private Position currentPosition;
    private boolean isInSettingsMode = false;
    private boolean isLocated = false;
    private boolean hasDestination = false;
    private String destinationName = "";
    private float speechSpeed = 1.0f;
    private VoskSpeechRecognizerService.Language currentLanguage = VoskSpeechRecognizerService.Language.CHINESE;
    private int navigationPace = 5000;
    private String lastNavigationInstruction = "";
    private String lastSpokenText = "";

    private LocalIntentEngine intentEngine;

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

    private TextView tvUnitDisplay;
    private boolean useSteps = false; // 默认米, 切换为步数

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.updateBaseContextLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load persisted speech speed
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        speechSpeed = prefs.getFloat("speechRate", 1.0f);

        // Sync language from LanguageManager
        String langCode = LanguageManager.getLanguage(this);
        currentLanguage = "en".equals(langCode)
                ? VoskSpeechRecognizerService.Language.ENGLISH
                : VoskSpeechRecognizerService.Language.CHINESE;

        setContentView(R.layout.activity_main);
        tvUnitDisplay = findViewById(R.id.tv_unit_display);

// 读取保存的单位偏好
        useSteps = getSharedPreferences("UserSettings", MODE_PRIVATE)
                .getBoolean("useSteps", false);

// 初始显示
        updateUnitDisplay();

        android.content.SharedPreferences prefs2 = getSharedPreferences("UserSettings", MODE_PRIVATE);
        boolean wasInSettings = prefs2.getBoolean("in_settings_mode", false);
        if (wasInSettings) {
            prefs2.edit().putBoolean("in_settings_mode", false).apply();
        }

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);

        initServices();
        initViews();
        initListeners();

        // Apply loaded speech rate to TTS service
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setSpeed(speechSpeed);
        }

        if (wasInSettings) {
            isInSettingsMode = true;
            settingsFullscreen.setVisibility(View.VISIBLE);
            updateSettingsDisplay();
        }

        new Thread(() -> {
            AppDatabase database = AppDatabase.getInstance();
            NavigationNodeDao dao = database.navigationNodeDao();

            List<NavigationNodeEntity> existingData = dao.getAllNodes();
            if (existingData == null || existingData.isEmpty()) {
                NavigationDataInitializer.initializeSampleData(dao);
                Log.d("MainActivity", "Navigation data initialized");
            }
        }).start();

    }

    private void updateUnitDisplay() {
        if (tvUnitDisplay == null) return;

        String unitText;
        if (currentLanguage == VoskSpeechRecognizerService.Language.ENGLISH) {
            unitText = useSteps ? "Unit: Steps" : "Unit: Meter";
        } else {
            unitText = useSteps ? "距离单位：步数" : "距离单位：米";
        }

        tvUnitDisplay.setText(unitText);
    }
    private List<Integer> findFloorsForLocation(String name) {
        Set<Integer> floors = new HashSet<>();
        List<PathEntity> allPaths = PathParser.getAllPaths();
        for (PathEntity path : allPaths) {
            if (path.getStartLabel_cn().contains(name) || path.getEndLabel_cn().contains(name)) {
                floors.add(path.getFloor());
            }
        }
        return new ArrayList<>(floors);
    }

    private void processVoiceCommand(String command) {
        if (command == null || command.trim().isEmpty()) return;

        LocalIntentEngine.IntentResult result = intentEngine.recognize(command);
        Log.d(TAG, "意图识别: " + result);

        switch (result.intent) {
            case NAVIGATE:
                if (result.destination != null) {
                    destinationName = result.destination;
                    hasDestination = true;
                    speak("正在导航到" + result.destination, speechSpeed);
                    startNavigation(result.destination);
                } else {
                    speak("请说出目的地", speechSpeed);
                }
                break;
            case SET_LOCATION:
                if (result.destination != null) {
                    Position pos = findPositionByName(result.destination);
                    if (pos != null) {
                        currentPosition = pos;
                        isLocated = true;
                        navigationService.setCurrentPosition(pos);
                        speak("已设置位置为" + pos.getLabel(), speechSpeed);
                    }
                }
                break;
            case STOP_NAVIGATION:
                if (navigationService.isNavigating()) {
                    navigationService.stopNavigation();
                    speak("导航已停止", speechSpeed);
                }
                break;
            case QUERY_LOCATION:
                speak(currentPosition != null ? "您在" + currentPosition.getLabel() : "位置未知", speechSpeed);
                break;
            case REPEAT:
                if (!lastSpokenText.isEmpty()) speak(lastSpokenText, speechSpeed);
                break;
            case HELP:
                speak(intentEngine.getHelpText(), speechSpeed);
                break;
            case LOCATE:
                startLocation();
                break;
            case QUERY_NEARBY:
                if (currentPosition != null) announceNearbyPOIs(currentPosition);
                else speak("请先定位", speechSpeed);
                break;
            case QUERY_PROGRESS:
                if (navigationService.isNavigating()) {
                    speak("导航进行中，请继续前进", speechSpeed);
                } else {
                    speak("当前没有进行导航", speechSpeed);
                }
                break;
            case START_NAVIGATION:
                if (hasDestination && currentPosition != null) {
                    startNavigation(destinationName);
                } else {
                    speak("请先设置目的地并定位", speechSpeed);
                }
                break;
            case SETTINGS:
                enterSettingsMode();
                break;
            case SPEED_UP:
                adjustSpeed(SPEED_STEP);
                break;
            case SPEED_DOWN:
                adjustSpeed(-SPEED_STEP);
                break;
            case EMERGENCY:
                btnEmergency.performClick();
                break;
            default:
                speak("未识别: " + command, speechSpeed);
        }
    }

    private void initServices() {
        Log.d(TAG, "初始化服务 - 使用工厂模式");

        serviceFactory = ServiceFactory.getInstance(this);
        ttsService = serviceFactory.getTtsService();
        voskService = serviceFactory.getVoskService();

        voskService.setRecognitionListener(new VoskSpeechRecognizerService.OnRecognitionListener() {
            @Override
            public void onResult(ArrayList<String> results) {
                if (results != null && !results.isEmpty()) {
                    String command = results.get(0);
                    Log.d(TAG, "Vosk识别结果: " + command);
                    runOnUiThread(() -> {
                        updateDisplay("你说: " + command);
                        processVoiceCommand(command);
                    });
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (voskService != null && voskService.isInitialized() && !isInSettingsMode) {
                        Log.d(TAG, "自动重启语音监听");
                        voskService.startListening();
                    }
                }, 1500);
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "Vosk识别错误: " + errorMsg);
                runOnUiThread(() -> {
                    speak("识别失败，请重试", speechSpeed);
                });
            }
        });

        intentEngine = new LocalIntentEngine(this);

        wifiScanner = new L_WiFiScannerServiceImpl();
        wifiScanner.init(this);
        locationService = new L_KnnLocationService(wifiScanner);
        locationService.init(this);

        navigationService = new CompassEnhancedNavigationService(ttsService, locationService);
        navigationService.initSensors(this);
        navigationService.loadUserSettings(this);

        navigationService.setPositionUpdateCallback(newPosition -> {
            runOnUiThread(() -> currentPosition = newPosition);
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
                lastNavigationInstruction = instruction;
                runOnUiThread(() -> updateDisplay(String.format("[%d/%d] %s", stepIndex, totalSteps, instruction)));
            }

            @Override public void onTurnWarning(String t, String a, int s) {
                runOnUiThread(() -> vibrate(100));
            }

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
                runOnUiThread(() -> {
                    if (!reachedDestination) updateDisplay("导航已停止");
                });
            }

            @Override
            public void onOffRoute(double deviationMeters) {
                runOnUiThread(() -> {
                    vibrate(300);
                    speak(String.format("偏离路线%.1f米", deviationMeters), speechSpeed);
                });
            }

            @Override public void onLocationUpdated(Position position) {
                runOnUiThread(() -> currentPosition = position);
            }

            @Override public void onDirectionUpdated(float heading, String cardinal) {}
        });

        pathStorage = new PathStorageService(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkServicesStatus();
        }, 4000);
    }

    private void checkServicesStatus() {
        StringBuilder status = new StringBuilder("服务状态: ");

        if (ttsService != null && ttsService.isReady()) {
            status.append("TTS就绪 ");
        } else {
            status.append("TTS未就绪 ");
        }

        if (voskService != null && voskService.isInitialized()) {
            status.append("Vosk就绪 ");
        } else {
            status.append("Vosk未就绪 ");
        }

        Log.d(TAG, status.toString());
        speak("语音系统初始化完成", speechSpeed);
    }

    private Position findPositionByName(String name) {
        List<PathEntity> allPaths = PathParser.getAllPaths();
        for (PathEntity path : allPaths) {
            if (path.getStartLabel_cn().contains(name) || name.contains(path.getStartLabel_cn())) {
                Position pos = new Position();
                pos.setLabel(path.getStartLabel_cn());
                pos.setFloor(path.getFloor());
                return pos;
            }
            if (path.getEndLabel_cn().contains(name) || name.contains(path.getEndLabel_cn())) {
                Position pos = new Position();
                pos.setLabel(path.getEndLabel_cn());
                pos.setFloor(path.getFloor());
                return pos;
            }
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
            String toSpeak = !lastNavigationInstruction.isEmpty() ? lastNavigationInstruction : lastSpokenText;
            if (!toSpeak.isEmpty()) { speak(toSpeak, speechSpeed); vibrate(50); }
        });

        setupLocateNavButton();

        btnVoiceAssistant.setOnClickListener(v -> {
            if (isInSettingsMode) {
                speak("请先退出设置模式", speechSpeed);
                return;
            }

            if (voskService != null && voskService.isInitialized()) {
                speak("请说出您的指令", speechSpeed);
                vibrate(100);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    voskService.startListening();
                    updateDisplay("正在聆听...");
                }, 800);
            } else {
                String input = etVoiceSimulate.getText().toString().trim();
                if (!input.isEmpty()) {
                    processVoiceCommand(input);
                    etVoiceSimulate.setText("");
                } else {
                    speak("语音识别尚未就绪，请在输入框输入指令", speechSpeed);
                }
            }
        });

        btnSettings.setOnClickListener(v -> enterSettingsMode());

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
                processVoiceCommand(input);
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
        if (isInSettingsMode) {
            speak("请先退出设置模式", speechSpeed);
            return;
        }
        vibrate(50);

        if (navigationService.isWaitingForElevator()) {
            navigationService.confirmElevatorArrival();
            return;
        }

        if (navigationService.isNavigating()) {
            speak("正在更新位置", speechSpeed);
            updateDisplay("定位更新中...");
            updateCurrentLocation();
        } else {
            startLocation();
        }
    }

    private void updateCurrentLocation() {
        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                runOnUiThread(() -> {
                    currentPosition = position;
                    if (navigationService.isNavigating()) {
                        navigationService.setCurrentPosition(position);
                    }
                    speak("当前位置：" + position.getLabel(), speechSpeed);
                    updateDisplay("当前位置：" + position.getLabel());
                });
            }
            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> speak("定位更新失败", speechSpeed));
            }
        });
    }

    private void onLongPressDetected() {
        vibrate(200);

        if (navigationService.isNavigating()) {
            navigationService.stopNavigation();
            hasDestination = false;
            destinationName = "";
            speak("导航已停止", speechSpeed);
            updateDisplay("导航已停止");
        } else {
            if (hasDestination && currentPosition != null) {
                speak("开始导航到" + destinationName, speechSpeed);
                startNavigation(destinationName);
            } else if (!hasDestination) {
                if (currentPosition != null) {
                    announceCurrentEnvironment();
                } else {
                    speak("请先定位或设置目的地", speechSpeed);
                }
            } else {
                speak("正在为您定位", speechSpeed);
                startLocation();
            }
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
            @Override
            public void onFailure(String e) {
                runOnUiThread(() -> {
                    isLocated = false;
                    speak("WiFi定位失败，请说我在加位置名称手动设置，例如我在门口", speechSpeed);
                    updateDisplay("定位失败 | 说\"我在XX\"设置位置");
                });
            }
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
        if (currentPosition == null) {
            speak("请先定位", speechSpeed);
            updateDisplay("请先定位");
            return;
        }

        destinationName = target;
        hasDestination = true;

        Position targetPosition = findPositionByName(target);
        if (targetPosition == null) {
            speak("未找到目的地：" + target, speechSpeed);
            updateDisplay("目的地不存在：" + target);
            hasDestination = false;
            destinationName = "";
            return;
        }

        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        navigationService.setNavigationConfig(navigationPace, speechSpeed, currentLanguage.locale);

        List<PathEntity> path = navigationService.calculatePath();
        if (path == null || path.isEmpty()) {
            speak("未找到路径到" + target, speechSpeed);
            hasDestination = false;
            destinationName = "";
            return;
        }

        speak("开始导航到" + target + "，预计" + path.size() + "个步骤", speechSpeed);
        updateDisplay("导航中 → " + target);
        navigationService.startContinuousNavigation();
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
        if (navigationService.isNavigating()) {
            status = "导航中 → " + destinationName;
            if (currentPosition != null) {
                status += " | 当前：" + currentPosition.getLabel();
            }
        } else if (hasDestination) {
            status = "已设目的地：" + destinationName;
            if (currentPosition != null) {
                status += " | 长按开始导航";
            } else {
                status += " | 请先定位";
            }
        } else {
            status = currentPosition != null ?
                    "当前位置：" + currentPosition.getLabel() + " | 长按查看环境" :
                    "单击定位 | 长按设置目的地";
        }

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
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setSpeed(speechSpeed);
        }

        // Save globally
        getSharedPreferences("UserSettings", MODE_PRIVATE)
                .edit()
                .putFloat("speechRate", speechSpeed)
                .apply();

        updateSettingsDisplay();
        speak(String.format("语速%.1f倍", speechSpeed), speechSpeed);
    }

    private void switchLanguage() {
        String current = LanguageManager.getLanguage(this);
        String newLang = "zh".equals(current) ? "en" : "zh";
        LanguageManager.setLanguage(this, newLang);

        currentLanguage = "en".equals(newLang)
                ? VoskSpeechRecognizerService.Language.ENGLISH
                : VoskSpeechRecognizerService.Language.CHINESE;

        // Update global TTS service locale
        Locale newLocale = "en".equals(newLang) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setLanguage(newLocale);
        }

        String msg = "zh".equals(newLang) ? "语言已切换为中文" : "Language switched to English";
        speak(msg, speechSpeed);

        recreate();
    }
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            useSteps = !useSteps;
            getSharedPreferences("UserSettings", MODE_PRIVATE)
                    .edit().putBoolean("useSteps", useSteps).apply();

            updateUnitDisplay();

            speak(currentLanguage == VoskSpeechRecognizerService.Language.ENGLISH
                            ? (useSteps ? "Switched to steps mode" : "Switched to meter mode")
                            : (useSteps ? "已切换为步数模式" : "已切换为米模式"),
                    speechSpeed);

            if (navigationService != null) navigationService.setUseSteps(useSteps);
            return true;

        }
    }

    private void switchPace() {
        paceIndex = (paceIndex + 1) % PACE_OPTIONS.length;
        navigationPace = PACE_OPTIONS[paceIndex];
        updateSettingsDisplay();
        speak(String.format("间隔%d秒", navigationPace / 1000), speechSpeed);
    }

    private void updateDisplay(String text) {
        if (tvTopDisplay != null) tvTopDisplay.setText(text);
    }

    private void speak(String text, float speed) {
        lastSpokenText = text;
        updateDisplay(text);

        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int volume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        Log.d(TAG, "TTS尝试播报: " + text + ", 音量=" + volume + "/" + maxVol + ", isReady=" + (ttsService != null ? ttsService.isReady() : "null"));

        if (volume == 0) {
            Log.w(TAG, "警告: 媒体音量为0!");
        }

        if (ttsService != null && ttsService.isReady()) {
            ttsService.speak(text, speed);
        } else {
            Log.e(TAG, "TTS未就绪! 尝试重新初始化");
            if (ttsService != null) ttsService.forceReinit();
        }
    }

    private void vibrate(long ms) {
        if (vibrator != null) vibrator.vibrate(ms);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statusUpdateHandler.removeCallbacksAndMessages(null);
        longPressHandler.removeCallbacksAndMessages(null);

        if (navigationService != null) {
            navigationService.stopNavigation();
        }

        try {
            if (serviceFactory != null) {
                serviceFactory.shutdown();
            }
        } catch (Exception e) {
            Log.w(TAG, "关闭服务异常: " + e.getMessage());
        }
    }
}