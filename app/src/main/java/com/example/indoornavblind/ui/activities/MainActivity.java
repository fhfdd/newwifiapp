package com.example.indoornavblind.ui.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
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

import com.example.indoornavblind.ui.view.GlowView;
import com.example.indoornavblind.R;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.database.entity.NavigationNodeEntity;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.WiFiScannerService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.lang.ref.WeakReference;
import android.os.Message;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long LONG_PRESS_DURATION = 800;

    private ServiceFactory serviceFactory;
    private C_TextToSpeechService ttsService;
    private VoskSpeechRecognizerService voskService;
    private CompassEnhancedNavigationService navigationService;
    private LocationService locationService;
    private WiFiScannerService wifiScanner;
    private PathStorageService pathStorage;
    private Vibrator vibrator;

    private TextView tvTopDisplay;
    private GlowView viewGlowOverlay;
    private EditText etVoiceSimulate;
    private Button btnLocateNav, btnVoiceAssistant, btnSettings, btnEmergency;
    private View settingsFullscreen;
    private TextView tvSpeedDisplay, tvLanguageDisplay, tvPaceDisplay;
    private TextView tvUnitDisplay;

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

    private long lastTtsEndTime = 0;
    private static final long TTS_END_ECHO_WINDOW_MS = 500;

    private LocalIntentEngine intentEngine;
    private VoskSpeechRecognizerService.OnRecognitionListener voskServiceListener;
    private static final float SPEED_STEP = 0.1f;
    private static final float SPEED_MIN = 0.5f;
    private static final float SPEED_MAX = 2.0f;
    private static final int[] PACE_OPTIONS = {2000, 3000, 5000, 8000};
    private int paceIndex = 2;

    private Set<String> unmatchedLocationCache = new LinkedHashSet<>();
    private static final String PREF_UNMATCHED_LOCATION_CACHE = "unmatched_location_cache";

    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> activityWeakReference;
        MyHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            this.activityWeakReference = new WeakReference<>(activity);
        }
        @Override
        public void handleMessage(Message msg) {
            // No action needed – handler exists only to avoid memory leaks
        }
    }

    private final MyHandler longPressHandler = new MyHandler(this);
    private final MyHandler statusUpdateHandler = new MyHandler(this);
    private Runnable longPressRunnable;
    private boolean isLongPressTriggered = false;
    private Runnable statusUpdateRunnable;

    private boolean isVoiceButtonPressed = false;
    private boolean isVoiceRecording = false;
    private GestureDetector gestureDetector;

    private final Handler glowSafetyHandler = new Handler(Looper.getMainLooper());
    private final Runnable glowSafetyOff = this::stopGlowEffect;
    private static final long GLOW_MAX_DURATION = 30_000L;

    private String L(String zh, String en) {
        return currentLanguage == VoskSpeechRecognizerService.Language.ENGLISH ? en : zh;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageManager.updateBaseContextLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        String langTag = prefs.getString("app_locale", null);
        if (langTag != null) {
            Locale locale = Locale.forLanguageTag(langTag);
            if (locale.getLanguage().equals("en")) {
                currentLanguage = VoskSpeechRecognizerService.Language.ENGLISH;
            } else {
                currentLanguage = VoskSpeechRecognizerService.Language.CHINESE;
            }
        } else {
            currentLanguage = VoskSpeechRecognizerService.Language.CHINESE;
        }
        applyLocaleWithoutRecreate(currentLanguage.locale);

        speechSpeed = prefs.getFloat("speechRate", 1.0f);
        setContentView(R.layout.activity_main);
        initViews();

        boolean wasInSettings = prefs.getBoolean("in_settings_mode", false);
        if (wasInSettings) {
            prefs.edit().putBoolean("in_settings_mode", false).apply();
        }

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);
        loadUnmatchedLocationCache();
        updateUnitDisplay();

        initServices();
        initListeners();

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
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        boolean useCm = prefs.getBoolean("useCm", false);
        String unitText;
        if (currentLanguage == VoskSpeechRecognizerService.Language.ENGLISH) {
            unitText = useCm ? "Unit: cm" : "Unit: steps";
        } else {
            unitText = useCm ? "距离单位：厘米" : "距离单位：步数";
        }
        tvUnitDisplay.setText(unitText);
    }

    private boolean isUsingCm() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        return prefs.getBoolean("useCm", false);
    }

    private void processVoiceCommand(String command) {
        if (command == null || command.trim().isEmpty()) return;
        LocalIntentEngine.IntentResult result = intentEngine.recognize(command);
        switch (result.intent) {
            case NAVIGATE:
                if (result.destination != null) {
                    destinationName = result.destination;
                    hasDestination = true;
                    speak(L("正在导航到" + result.destination, "Navigating to " + result.destination), speechSpeed);
                    startNavigation(result.destination);
                } else {
                    speak(L("请说出目的地", "Please say the destination"), speechSpeed);
                }
                break;
            case SET_LOCATION:
                if (result.destination == null) break;
                if (!isLocated || currentPosition == null) {
                    speak(L("正在定位到" + result.destination, "Locating to " + result.destination), speechSpeed);
                    updateDisplay(L("定位到 " + result.destination + "…", "Locating to " + result.destination + "..."));
                }
                Position pos = findPositionByNameOrCache(result.destination);
                if (pos != null) {
                    currentPosition = pos;
                    isLocated = true;
                    navigationService.setCurrentPosition(pos);
                    speak(L("已定位到" + pos.getLabel(), "Located at " + pos.getLabel()), speechSpeed);
                    updateDisplay(L("当前位置：" + pos.getLabel(), "Location: " + pos.getLabel()));
                } else {
                    addToUnmatchedLocationCache(result.destination);
                    speak(L("未找到位置" + result.destination + "，已缓存，正在尝试WiFi定位", "Location " + result.destination + " not found, cached, trying WiFi positioning"), speechSpeed);
                    updateDisplay(L("未找到「" + result.destination + "」，已缓存，正在定位…", "\"" + result.destination + "\" not found, cached, locating..."));
                    startLocation();
                }
                break;
            case STOP_NAVIGATION:
                if (navigationService.isNavigating()) {
                    navigationService.stopNavigation();
                    speak(L("导航已停止", "Navigation stopped"), speechSpeed);
                }
                break;
            case QUERY_LOCATION:
                speak(currentPosition != null ? L("您在" + currentPosition.getLabel(), "You are at " + currentPosition.getLabel()) : L("位置未知", "Location unknown"), speechSpeed);
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
                else speak(L("请先定位", "Please locate first"), speechSpeed);
                break;
            case QUERY_PROGRESS:
                if (navigationService.isNavigating()) {
                    speak(L("导航进行中，请继续前进", "Navigation in progress, please continue"), speechSpeed);
                } else {
                    speak(L("当前没有进行导航", "No active navigation"), speechSpeed);
                }
                break;
            case START_NAVIGATION:
                if (hasDestination && currentPosition != null) {
                    startNavigation(destinationName);
                } else {
                    speak(L("请先设置目的地并定位", "Please set destination and locate first"), speechSpeed);
                }
                break;
            case ENTER_SETTINGS:
                enterSettingsMode();
                break;
            case EXIT_SETTINGS:
                if (isInSettingsMode) exitSettingsMode();
                else speak(L("当前不在设置模式", "Not in settings mode"), speechSpeed);
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
            case VOICE_ASSISTANT:
                btnVoiceAssistant.performClick();
                break;
            case CONTINUE_NAVIGATION:
                if (hasDestination && currentPosition != null) startNavigation(destinationName);
                else speak(L("请先设置目的地并定位", "Please set destination and locate first"), speechSpeed);
                break;
            case FLOOR_UP:
                speak(L("请使用楼梯或电梯上楼", "Please take the stairs or elevator up"), speechSpeed);
                break;
            case FLOOR_DOWN:
                speak(L("请使用楼梯或电梯下楼", "Please take the stairs or elevator down"), speechSpeed);
                break;
            default:
                speak(L("未识别: " + command, "Not recognized: " + command), speechSpeed);
        }
    }

    private void initServices() {
        Log.d(TAG, "初始化服务 - 使用工厂模式");
        serviceFactory = ServiceFactory.getInstance(this);
        ttsService = serviceFactory.getTtsService();

        ttsService.setTTSSpeechListener(new C_TextToSpeechService.TTSSpeechListener() {
            @Override
            public void onSpeechStart() {
                runOnUiThread(() -> {
                    startGlowEffect();
                    glowSafetyHandler.removeCallbacks(glowSafetyOff);
                    glowSafetyHandler.postDelayed(glowSafetyOff, GLOW_MAX_DURATION);
                });
            }
            @Override
            public void onSpeechDone() {
                lastTtsEndTime = System.currentTimeMillis();
                runOnUiThread(() -> {
                    glowSafetyHandler.removeCallbacks(glowSafetyOff);
                    stopGlowEffect();
                });
            }
            @Override
            public void onSpeechError(String errorMessage) {
                lastTtsEndTime = System.currentTimeMillis();
                runOnUiThread(() -> {
                    glowSafetyHandler.removeCallbacks(glowSafetyOff);
                    stopGlowEffect();
                });
            }
        });

        voskService = serviceFactory.getVoskService();
        voskServiceListener = new VoskSpeechRecognizerService.OnRecognitionListener() {
            @Override
            public void onResult(ArrayList<String> results) {
                if (results != null && !results.isEmpty()) {
                    String command = cleanRecognizedText(results.get(0));
                    long timeSinceTtsEnd = System.currentTimeMillis() - lastTtsEndTime;
                    if (timeSinceTtsEnd < TTS_END_ECHO_WINDOW_MS) return;
                    runOnUiThread(() -> {
                        updateDisplay(L("识别: " + command, "Recognized: " + command));
                        vibrate(30);
                        processVoiceCommand(command);
                    });
                }
            }
            @Override
            public void onError(String errorMsg) {
                long timeSinceTtsEnd = System.currentTimeMillis() - lastTtsEndTime;
                if (timeSinceTtsEnd < TTS_END_ECHO_WINDOW_MS) return;
                runOnUiThread(() -> speak(L("识别失败，请重试", "Recognition failed, please try again"), speechSpeed));
            }
        };
        voskService.setRecognitionListener(voskServiceListener);

        intentEngine = new LocalIntentEngine(this);
        wifiScanner = new L_WiFiScannerServiceImpl();
        wifiScanner.init(this);
        locationService = new L_KnnLocationService(wifiScanner);
        locationService.init(this);
        navigationService = new CompassEnhancedNavigationService(ttsService, locationService);
        navigationService.initSensors(this);
        navigationService.loadUserSettings(this);

        navigationService.setPositionUpdateCallback(newPosition -> runOnUiThread(() -> currentPosition = newPosition));

        navigationService.setNavigationEventCallback(new CompassEnhancedNavigationService.NavigationEventCallback() {
            @Override public void onNavigationStarted(String from, String to, int totalSteps, double totalDistance, int estimatedSeconds) {
                runOnUiThread(() -> {
                    updateDisplay(L("导航开始：" + from + " → " + to, "Navigation started: " + from + " → " + to));
                    vibrate(200);
                });
            }
            @Override public void onStepAnnounced(int stepIndex, int totalSteps, String instruction, String absoluteDirection) {
                lastNavigationInstruction = instruction;
                final String finalInstruction = instruction;
                final float finalSpeed = speechSpeed;
                runOnUiThread(() -> {
                    tvTopDisplay.setText(finalInstruction);
                    speak(finalInstruction, finalSpeed);
                });
            }
            @Override public void onTurnWarning(String t, String a, int s) { runOnUiThread(() -> vibrate(100)); }
            @Override public void onProgressUpdate(int c, int r, double d) {}
            @Override public void onArrival(String destination, String detailInfo) {
                runOnUiThread(() -> {
                    hasDestination = false;
                    destinationName = "";
                    updateDisplay(L("已到达：" + destination, "Arrived: " + destination));
                    vibrate(500);
                    if (pathStorage != null && currentPosition != null) pathStorage.recordRoute(currentPosition.getLabel(), destination);
                });
            }
            @Override public void onNavigationStopped(boolean reachedDestination) {
                if (!reachedDestination) runOnUiThread(() -> updateDisplay(L("导航已停止", "Navigation stopped")));
            }
            @Override public void onOffRoute(double deviationMeters) {
                runOnUiThread(() -> {
                    vibrate(300);
                    boolean useCm = isUsingCm();
                    if (useCm) {
                        double deviationCm = deviationMeters * 100;
                        speak(L(String.format(Locale.US, "偏离路线%.0f厘米", deviationCm), String.format(Locale.US, "Off route by %.0f cm", deviationCm)), speechSpeed);
                    } else {
                        speak(L(String.format(Locale.US, "偏离路线%.1f米", deviationMeters), String.format(Locale.US, "Off route by %.1f meters", deviationMeters)), speechSpeed);
                    }
                });
            }
            @Override public void onLocationUpdated(Position position) { runOnUiThread(() -> currentPosition = position); }
            @Override public void onDirectionUpdated(float heading, String cardinal) {}
        });

        pathStorage = new PathStorageService(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        statusUpdateHandler.postDelayed(this::checkServicesStatus, 4000);
    }

    private void checkServicesStatus() {
        StringBuilder status = new StringBuilder("服务状态: ");
        if (ttsService != null && ttsService.isReady()) status.append("TTS就绪 ");
        else status.append("TTS未就绪 ");
        if (voskService != null && voskService.isInitialized()) status.append("Vosk就绪 ");
        else status.append("Vosk未就绪 ");
        Log.d(TAG, status.toString());
        speak(L("语音系统初始化完成", "Voice system initialization complete"), speechSpeed);
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

    private Position findPositionByNameOrCache(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String n = name.trim();
        Position pos = findPositionByName(n);
        if (pos != null) return pos;
        for (String cached : unmatchedLocationCache) {
            if (cached.equalsIgnoreCase(n) || n.contains(cached) || cached.contains(n)) {
                Position fromCache = new Position();
                fromCache.setLabel(cached);
                fromCache.setFloor(0);
                return fromCache;
            }
        }
        return null;
    }

    private void loadUnmatchedLocationCache() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        String saved = prefs.getString(PREF_UNMATCHED_LOCATION_CACHE, "");
        unmatchedLocationCache = new LinkedHashSet<>();
        if (!saved.isEmpty()) {
            for (String s : saved.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) unmatchedLocationCache.add(t);
            }
        }
    }

    private void saveUnmatchedLocationCache() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        prefs.edit().putString(PREF_UNMATCHED_LOCATION_CACHE, String.join(",", unmatchedLocationCache)).apply();
    }

    private void addToUnmatchedLocationCache(String locationName) {
        if (locationName == null || locationName.trim().isEmpty()) return;
        unmatchedLocationCache.add(locationName.trim());
        saveUnmatchedLocationCache();
    }

    private void initViews() {
        tvTopDisplay = findViewById(R.id.tv_top_display);
        viewGlowOverlay = findViewById(R.id.view_glow_overlay);
        etVoiceSimulate = findViewById(R.id.et_voice_simulate);
        btnLocateNav = findViewById(R.id.btn_locate_nav);
        btnVoiceAssistant = findViewById(R.id.btn_voice_assistant);
        btnSettings = findViewById(R.id.btn_settings);
        btnEmergency = findViewById(R.id.btn_emergency);
        settingsFullscreen = findViewById(R.id.settings_fullscreen);
        tvSpeedDisplay = findViewById(R.id.tv_speed_display);
        tvLanguageDisplay = findViewById(R.id.tv_language_display);
        tvPaceDisplay = findViewById(R.id.tv_pace_display);
        tvUnitDisplay = findViewById(R.id.tv_unit_display);
    }

    private void initListeners() {
        tvTopDisplay.setOnClickListener(v -> {
            String toSpeak = !lastNavigationInstruction.isEmpty() ? lastNavigationInstruction : lastSpokenText;
            if (!toSpeak.isEmpty()) { speak(toSpeak, speechSpeed); vibrate(50); }
        });
        setupLocateNavButton();

        btnVoiceAssistant.setOnTouchListener((v, event) -> {
            if (isInSettingsMode) {
                speak(L("请先退出设置模式", "Please exit settings mode first"), speechSpeed);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.performClick(); // for accessibility
                isVoiceButtonPressed = true;
                handleVoiceButtonPress();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (isVoiceButtonPressed) {
                    isVoiceButtonPressed = false;
                    handleVoiceButtonRelease();
                }
                return true;
            }
            return false;
        });

        btnSettings.setOnClickListener(v -> enterSettingsMode());

        btnEmergency.setOnClickListener(v -> {
            speak(L("紧急求助已触发，正在拨号", "Emergency help triggered, dialing"), speechSpeed);
            vibrate(500);
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:+85212345678"));
            startActivity(intent);
            if (currentPosition != null) {
                String emMsg = L("当前位置：" + currentPosition.getLabel() + "。" + navigationService.getCurrentDirectionInfo(),
                        "Current location: " + currentPosition.getLabel() + ". " + navigationService.getCurrentDirectionInfo());
                statusUpdateHandler.postDelayed(() -> speak(emMsg, speechSpeed), 1000);
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
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.performClick(); // for accessibility
                isLongPressTriggered = false;
                longPressRunnable = () -> {
                    isLongPressTriggered = true;
                    onLongPressDetected();
                };
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                longPressHandler.removeCallbacks(longPressRunnable);
                if (!isLongPressTriggered) onSingleClickDetected();
                return true;
            }
            return false;
        });
    }

    private void onSingleClickDetected() {
        if (isInSettingsMode) {
            speak(L("请先退出设置模式", "Please exit settings mode first"), speechSpeed);
            return;
        }
        vibrate(50);
        if (navigationService.isWaitingForElevator()) {
            navigationService.confirmElevatorArrival();
            return;
        }
        if (navigationService.isNavigating()) {
            speak(L("正在更新位置", "Updating location"), speechSpeed);
            updateDisplay(L("定位更新中...", "Updating location..."));
            updateCurrentLocation();
        } else {
            startLocation();
        }
    }

    private void updateCurrentLocation() {
        locationService.locate(new LocationService.LocationCallback() {
            @Override public void onSuccess(Position position) {
                runOnUiThread(() -> {
                    currentPosition = position;
                    if (navigationService.isNavigating()) navigationService.setCurrentPosition(position);
                    speak(L("当前位置：" + position.getLabel(), "Current location: " + position.getLabel()), speechSpeed);
                    updateDisplay(L("当前位置：" + position.getLabel(), "Current location: " + position.getLabel()));
                });
            }
            @Override public void onFailure(String error) {
                runOnUiThread(() -> speak(L("定位更新失败", "Location update failed"), speechSpeed));
            }
        });
    }

    private void onLongPressDetected() {
        vibrate(200);
        if (navigationService.isNavigating()) {
            navigationService.stopNavigation();
            hasDestination = false;
            destinationName = "";
            speak(L("导航已停止", "Navigation stopped"), speechSpeed);
            updateDisplay(L("导航已停止", "Navigation stopped"));
        } else {
            if (hasDestination && currentPosition != null) {
                speak(L("开始导航到" + destinationName, "Starting navigation to " + destinationName), speechSpeed);
                startNavigation(destinationName);
            } else if (!hasDestination) {
                if (currentPosition != null) announceCurrentEnvironment();
                else speak(L("请先定位或设置目的地", "Please locate or set a destination first"), speechSpeed);
            } else {
                speak(L("正在为您定位", "Locating for you"), speechSpeed);
                startLocation();
            }
        }
    }

    private void announceCurrentEnvironment() {
        if (currentPosition == null) return;
        String msg = L("当前在" + currentPosition.getLabel() + "。" + navigationService.getCurrentDirectionInfo(),
                "Currently at " + currentPosition.getLabel() + ". " + navigationService.getCurrentDirectionInfo());
        speak(msg, speechSpeed);
        updateDisplay(L("当前：" + currentPosition.getLabel(), "Current: " + currentPosition.getLabel()));
        statusUpdateHandler.postDelayed(() -> {
            announceNearbyPOIs(currentPosition);
            if (!hasDestination) {
                statusUpdateHandler.postDelayed(() -> {
                    List<String> recs = pathStorage.recommendDestinations(currentPosition.getLabel(), 3);
                    speak(!recs.isEmpty() ? L("推荐目的地：" + String.join("、", recs), "Recommended destinations: " + String.join(", ", recs))
                            : L("请说出目的地", "Please say the destination"), speechSpeed);
                }, 3000);
            }
        }, 2000);
    }

    private void setupSettingsGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (!isInSettingsMode) return false;
                float dX = e2.getX() - e1.getX();
                float dY = e2.getY() - e1.getY();
                if (Math.abs(dX) > Math.abs(dY) && Math.abs(dX) > 100) {
                    switchLanguage();
                    return true;
                }
                if (Math.abs(dY) > 100) {
                    adjustSpeed(dY < 0 ? SPEED_STEP : -SPEED_STEP);
                    return true;
                }
                return false;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (isInSettingsMode) { exitSettingsMode(); return true; }
                return false;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isInSettingsMode) { switchPace(); return true; }
                return false;
            }
        });
        settingsFullscreen.setOnTouchListener((v, event) -> { gestureDetector.onTouchEvent(event); return true; });
    }

    private void startLocation() {
        speak(L("正在定位", "Locating"), speechSpeed);
        vibrate(100);
        updateDisplay(L("定位中...", "Locating..."));
        locationService.locate(new LocationService.LocationCallback() {
            @Override public void onSuccess(Position position) {
                runOnUiThread(() -> {
                    currentPosition = position;
                    isLocated = true;
                    navigationService.setCurrentPosition(position);
                    speak(L("定位成功，当前在" + position.getLabel(), "Located successfully, you are at " + position.getLabel()), speechSpeed);
                    vibrate(200);
                    statusUpdateHandler.postDelayed(() -> {
                        announceNearbyPOIs(position);
                        if (hasDestination) {
                            statusUpdateHandler.postDelayed(() -> speak(L("目的地" + destinationName + "，长按开始导航", "Destination " + destinationName + ", long press to start navigation"), speechSpeed), 2000);
                        }
                    }, 2000);
                });
            }
            @Override public void onFailure(String e) {
                runOnUiThread(() -> {
                    isLocated = false;
                    speak(L("WiFi定位失败，请说我在加位置名称手动设置，例如我在门口", "WiFi positioning failed. Say \"I am at\" plus a location name to set manually"), speechSpeed);
                    updateDisplay(L("定位失败 | 说\"我在XX\"设置位置", "Location failed | Say \"I am at XX\" to set location"));
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
        if (!nearby.isEmpty()) {
            List<String> top = nearby.subList(0, Math.min(3, nearby.size()));
            speak(L("附近有：" + String.join("、", top), "Nearby: " + String.join(", ", top)), speechSpeed);
        }
    }

    private void startNavigation(String target) {
        if (currentPosition == null) {
            speak(L("请先定位", "Please locate first"), speechSpeed);
            updateDisplay(L("请先定位", "Please locate first"));
            return;
        }
        destinationName = target;
        hasDestination = true;
        Position targetPosition = findPositionByName(target);
        if (targetPosition == null) {
            speak(L("未找到目的地：" + target, "Destination not found: " + target), speechSpeed);
            updateDisplay(L("目的地不存在：" + target, "Destination not found: " + target));
            hasDestination = false;
            destinationName = "";
            return;
        }
        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        navigationService.setNavigationConfig(navigationPace, speechSpeed, currentLanguage.locale);
        List<PathEntity> path = navigationService.calculatePath();
        if (path == null || path.isEmpty()) {
            speak(L("未找到路径到" + target, "No route found to " + target), speechSpeed);
            hasDestination = false;
            destinationName = "";
            return;
        }
        speak(L("开始导航到" + target + "，预计" + path.size() + "个步骤", "Starting navigation to " + target + ", approximately " + path.size() + " steps"), speechSpeed);
        updateDisplay(L("导航中 → " + target, "Navigating → " + target));
        navigationService.startContinuousNavigation();
    }

    private void startStatusUpdater() {
        statusUpdateRunnable = () -> {
            updateNavigationStatus();
            statusUpdateHandler.postDelayed(statusUpdateRunnable, 2000);
        };
        statusUpdateHandler.post(statusUpdateRunnable);
    }

    private void updateNavigationStatus() {
        if (isInSettingsMode) return;
        String status;
        if (navigationService.isNavigating()) {
            status = L("导航中 → ", "Navigating → ") + destinationName;
            if (currentPosition != null) status += L(" | 当前：", " | Current: ") + currentPosition.getLabel();
        } else if (hasDestination) {
            status = L("已设目的地：", "Destination: ") + destinationName;
            if (currentPosition != null) status += L(" | 长按开始导航", " | Long press to start navigation");
            else status += L(" | 请先定位", " | Please locate first");
        } else {
            status = currentPosition != null ?
                    L("当前位置：" + currentPosition.getLabel() + " | 长按查看环境", "Current: " + currentPosition.getLabel() + " | Long press for environment") :
                    L("单击定位 | 长按设置目的地", "Tap to locate | Long press to set destination");
        }
        updateDisplay(status);
    }

    private void enterSettingsMode() {
        isInSettingsMode = true;
        settingsFullscreen.setVisibility(View.VISIBLE);
        speak(L("进入设置。上下滑动调语速，左右滑动切换语言，单击切换间隔，双击退出", "Entering settings. Swipe up/down to adjust speed, left/right to switch language, tap to switch interval, double tap to exit"), speechSpeed);
        updateSettingsDisplay();
    }

    private void exitSettingsMode() {
        isInSettingsMode = false;
        settingsFullscreen.setVisibility(View.GONE);
        updateUnitDisplay();
        speak(L(String.format("设置完成。语速%.1f倍，%s", speechSpeed, currentLanguage.displayName), String.format(Locale.US, "Settings saved. Speed %.1fx, %s", speechSpeed, currentLanguage.displayName)), speechSpeed);
    }

    private void updateSettingsDisplay() {
        tvSpeedDisplay.setText(L(String.format("语速：%.1f倍", speechSpeed), String.format(Locale.US, "Speed: %.1fx", speechSpeed)));
        tvLanguageDisplay.setText(L("语言：", "Language: ") + currentLanguage.displayName);
        tvPaceDisplay.setText(L(String.format("间隔：%d秒", navigationPace / 1000), String.format(Locale.US, "Interval: %ds", navigationPace / 1000)));
    }

    private void adjustSpeed(float delta) {
        speechSpeed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speechSpeed + delta));
        if (ttsService != null && ttsService.isReady()) ttsService.setSpeed(speechSpeed);
        getSharedPreferences("UserSettings", MODE_PRIVATE).edit().putFloat("speechRate", speechSpeed).apply();
        updateSettingsDisplay();
        speak(L(String.format("语速%.1f倍", speechSpeed), String.format(Locale.US, "Speech speed %.1fx", speechSpeed)), speechSpeed);
    }

    private void switchLanguage() {
        if (currentLanguage == VoskSpeechRecognizerService.Language.CHINESE)
            switchToLanguage(VoskSpeechRecognizerService.Language.ENGLISH);
        else
            switchToLanguage(VoskSpeechRecognizerService.Language.CHINESE);
    }

    private boolean isSwitchingLanguage = false;

    private void switchToLanguage(VoskSpeechRecognizerService.Language language) {
        if (isSwitchingLanguage) return;
        if (currentLanguage == language) return;

        // Only allow Chinese or English
        VoskSpeechRecognizerService.Language safeLang = language;
        if (safeLang != VoskSpeechRecognizerService.Language.CHINESE &&
                safeLang != VoskSpeechRecognizerService.Language.ENGLISH) {
            safeLang = VoskSpeechRecognizerService.Language.CHINESE;
        }

        isSwitchingLanguage = true;
        final VoskSpeechRecognizerService.Language finalLanguage = safeLang;
        final Locale finalLocale = safeLang.locale;

        currentLanguage = safeLang;

        if (ttsService != null && ttsService.isReady()) {
            ttsService.setLanguage(safeLang.locale);
        }

        try {
            if (serviceFactory != null) {
                serviceFactory.switchLanguage(safeLang);
            }
        } catch (Exception e) {
            Log.e(TAG, "Vosk切换失败: " + e.getMessage());
        }

        String msg = (safeLang == VoskSpeechRecognizerService.Language.ENGLISH)
                ? "Switched to " + safeLang.displayName
                : "已切换到" + safeLang.displayName;
        if (ttsService != null && ttsService.isReady()) {
            ttsService.speak(msg, speechSpeed);
        }

        statusUpdateHandler.postDelayed(() -> {
            updateAppLocale(finalLocale);
            isSwitchingLanguage = false;
        }, 1500);
    }

    private void updateAppLocale(Locale locale) {
        getSharedPreferences("UserSettings", MODE_PRIVATE).edit()
                .putString("app_locale", locale.toLanguageTag())
                .putBoolean("in_settings_mode", isInSettingsMode)
                .apply();
        android.content.res.Resources res = getResources();
        android.content.res.Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
        recreate();
    }

    private void applyLocaleWithoutRecreate(Locale locale) {
        String lang = locale.getLanguage();
        if (!lang.equals("zh") && !lang.equals("en")) locale = Locale.SIMPLIFIED_CHINESE;
        android.content.res.Resources res = getResources();
        android.content.res.Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    private void switchPace() {
        paceIndex = (paceIndex + 1) % PACE_OPTIONS.length;
        navigationPace = PACE_OPTIONS[paceIndex];
        updateSettingsDisplay();
        speak(L(String.format("间隔%d秒", navigationPace / 1000), String.format(Locale.US, "Interval %d seconds", navigationPace / 1000)), speechSpeed);
    }

    private void updateDisplay(String text) { if (tvTopDisplay != null) tvTopDisplay.setText(text); }

    private String cleanRecognizedText(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+", " ").replaceAll("[\\n\\r\\t]", " ").replaceAll("[　]+", "").trim();
    }

    private void speak(String text, float speed) {
        lastSpokenText = text;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int volume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        Log.d(TAG, "TTS尝试播报: " + text + ", 音量=" + volume);
        if (ttsService != null && ttsService.isReady()) ttsService.speak(text, speed);
        else if (ttsService != null) ttsService.forceReinit();
    }

    private void vibrate(long ms) { if (vibrator != null) vibrator.vibrate(ms); }

    private void startGlowEffect() { if (viewGlowOverlay != null) viewGlowOverlay.startGlow(); }
    private void stopGlowEffect() { if (viewGlowOverlay != null) viewGlowOverlay.stopGlow(); }

    private void handleVoiceButtonPress() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            speak(L("需要录音权限", "Recording permission required"), speechSpeed);
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 100);
            isVoiceButtonPressed = false;
            return;
        }
        if (voskService != null && voskService.isInitialized()) {
            vibrate(80);
            btnVoiceAssistant.setScaleX(0.95f);
            btnVoiceAssistant.setScaleY(0.95f);
            isVoiceRecording = true;
            voskService.startListening();
            updateDisplay(L("正在聆听...", "Listening..."));
            startGlowEffect();
        } else {
            String input = etVoiceSimulate.getText().toString().trim();
            if (!input.isEmpty()) { processVoiceCommand(input); etVoiceSimulate.setText(""); }
            else speak(L("语音识别尚未就绪，请在输入框输入指令", "Voice recognition not ready yet, please type in the input box"), speechSpeed);
            isVoiceButtonPressed = false;
        }
    }

    private void handleVoiceButtonRelease() {
        btnVoiceAssistant.setScaleX(1.0f);
        btnVoiceAssistant.setScaleY(1.0f);
        if (isVoiceRecording) {
            isVoiceRecording = false;
            if (voskService != null) voskService.stopListening();
            stopGlowEffect();
            vibrate(50);
            updateDisplay(L("识别中...", "Recognizing..."));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopGlowEffect();
        statusUpdateHandler.removeCallbacksAndMessages(null);
        longPressHandler.removeCallbacksAndMessages(null);
        glowSafetyHandler.removeCallbacksAndMessages(null);
        if (navigationService != null) navigationService.stopNavigation();
        try { if (serviceFactory != null) serviceFactory.shutdown(); } catch (Exception e) { Log.w(TAG, "关闭服务异常: " + e.getMessage()); }
    }
}