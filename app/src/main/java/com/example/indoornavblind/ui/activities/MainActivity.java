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

    // 服务相关
    private ServiceFactory serviceFactory;
    private C_TextToSpeechService ttsService;
    private VoskSpeechRecognizerService voskService;
    private CompassEnhancedNavigationService navigationService;
    private LocationService locationService;
    private WiFiScannerService wifiScanner;
    private PathStorageService pathStorage;
    private Vibrator vibrator;

    // 控件相关
    private TextView tvTopDisplay;
    private GlowView viewGlowOverlay; // 绿色光晕闪烁覆盖层（自定义呼吸灯View）
    private EditText etVoiceSimulate;
    private Button btnLocateNav, btnVoiceAssistant, btnSettings, btnEmergency;
    private View settingsFullscreen;
    private TextView tvSpeedDisplay, tvLanguageDisplay, tvPaceDisplay;
    private TextView tvUnitDisplay;   // 距离单位显示
    private TextView tvStrideDisplay; // 步幅显示

    // 状态相关
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
    private String lastRecognizedText = ""; // 最后识别的Vosk内容

    // TTS回声保护机制（防止TTS声音被识别成指令）
    private long lastTtsEndTime = 0;
    private static final long TTS_END_ECHO_WINDOW_MS = 500; // TTS结束后500ms内的识别结果忽略

    // 其他配置
    private LocalIntentEngine intentEngine;
    private VoskSpeechRecognizerService.OnRecognitionListener voskServiceListener;
    private static final float SPEED_STEP = 0.1f;
    private static final float SPEED_MIN = 0.5f;
    private static final float SPEED_MAX = 2.0f;
    private static final int[] PACE_OPTIONS = {2000, 3000, 5000, 8000};
    private static final float DEFAULT_STRIDE_CM = 60f;
    private int paceIndex = 2;

    // 未匹配位置缓存（用户说”我在xx”但xx不在已知地点列表中时写入）
    private Set<String> unmatchedLocationCache = new LinkedHashSet<>();
    private static final String PREF_UNMATCHED_LOCATION_CACHE = "unmatched_location_cache";

    // Handler（避免内存泄漏，使用静态内部类）
    private static class MyHandler extends Handler {
        public final WeakReference<MainActivity> activityWeakReference;

        public MyHandler(MainActivity activity) {
            super(Looper.getMainLooper());
            this.activityWeakReference = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = activityWeakReference.get();
            if (activity != null) {
                // 空实现，仅用于避免内存泄漏，原Handler逻辑迁移至对应方法
            }
        }
    }

    private final MyHandler longPressHandler = new MyHandler(this);
    private final MyHandler statusUpdateHandler = new MyHandler(this);
    private Runnable longPressRunnable;
    private boolean isLongPressTriggered = false;
    private Runnable statusUpdateRunnable;

    // 语音助手按住说话相关
    private boolean isVoiceButtonPressed = false;
    private boolean isVoiceRecording = false;
    private GestureDetector gestureDetector;

    // ✅ 新增：绿色光晕超时保险（防止TTS的onSpeechDone丢失导致卡绿）
    private final Handler glowSafetyHandler = new Handler(Looper.getMainLooper());
    private final Runnable glowSafetyOff = this::stopGlowEffect;
    private static final long GLOW_MAX_DURATION = 30_000L; // 30秒兜底

    /**
     * ✅ 新增：根据当前语言返回对应文本
     * 英文模式返回英文，其它（中文/粤语）返回中文
     */
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

        // 1. 恢复语言设置
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        String langTag = prefs.getString("app_locale", null);
        if (langTag != null) {
            Locale locale = Locale.forLanguageTag(langTag);
            if (locale.getLanguage().equals("en")) {
                currentLanguage = VoskSpeechRecognizerService.Language.ENGLISH;
            } else if (locale.toLanguageTag().equals("zh-HK")) {
                currentLanguage = VoskSpeechRecognizerService.Language.CANTONESE;
            } else {
                currentLanguage = VoskSpeechRecognizerService.Language.CHINESE;
            }
        }
        applyLocaleWithoutRecreate(currentLanguage.locale);

        // 2. 加载语速设置
        speechSpeed = prefs.getFloat("speechRate", 1.0f);

        // 3. 加载布局
        setContentView(R.layout.activity_main);

        // 4. 初始化控件
        initViews();

        // 5. 恢复设置模式标记
        SharedPreferences prefs2 = getSharedPreferences("UserSettings", MODE_PRIVATE);
        boolean wasInSettings = prefs2.getBoolean("in_settings_mode", false);
        if (wasInSettings) {
            prefs2.edit().putBoolean("in_settings_mode", false).apply(); // 清除标记
        }

        // 6. 初始化基础工具和缓存
        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);
        loadUnmatchedLocationCache();
        updateUnitDisplay(); // 初始化单位显示

        // 7. 初始化服务
        initServices();

        // 8. 初始化监听器
        initListeners();

        // 9. 应用已加载的语速设置
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setSpeed(speechSpeed);
        }

        // 10. 恢复设置模式
        if (wasInSettings) {
            isInSettingsMode = true;
            settingsFullscreen.setVisibility(View.VISIBLE);
            updateSettingsDisplay();
        }

        // 11. 初始化导航数据（子线程，避免阻塞UI）
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

    /**
     * 更新主屏幕距离单位显示
     * 从SharedPreferences读取useCm和strideCm偏好设置
     */
    private void updateUnitDisplay() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        boolean useCm = prefs.getBoolean("useCm", false);
        float strideCm = prefs.getFloat("strideCm", DEFAULT_STRIDE_CM);

        if (tvUnitDisplay != null) {
            String unitText;
            if (currentLanguage == VoskSpeechRecognizerService.Language.ENGLISH) {
                unitText = useCm ? "Distance Unit: cm" : "Distance Unit: steps";
            } else {
                unitText = useCm ? "距离单位：厘米" : "距离单位：步数";
            }
            tvUnitDisplay.setText(unitText);
            Log.d(TAG, "Unit display updated: " + unitText + " (useCm=" + useCm + ")");
        }

        if (tvStrideDisplay != null) {
            String strideText;
            if (currentLanguage == VoskSpeechRecognizerService.Language.ENGLISH) {
                strideText = String.format(Locale.US, "Stride: %.0f cm", strideCm);
            } else {
                strideText = String.format(Locale.CHINA, "步幅：%.0f厘米", strideCm);
            }
            tvStrideDisplay.setText(strideText);
            Log.d(TAG, "Stride display updated: " + strideText);
        }
    }

    /**
     * 获取当前距离单位偏好设置
     *
     * @return true=厘米，false=步数
     */
    private boolean isUsingCm() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        return prefs.getBoolean("useCm", false);
    }

    /**
     * 获取当前步幅设置（厘米）
     */
    private float getStrideCm() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        return prefs.getFloat("strideCm", DEFAULT_STRIDE_CM);
    }

    /**
     * 根据位置名称查找所在楼层
     */
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

    /**
     * 处理语音指令（整合两份代码的指令逻辑，去重优化）
     */
    private void processVoiceCommand(String command) {
        if (command == null || command.trim().isEmpty()) return;

        LocalIntentEngine.IntentResult result = intentEngine.recognize(command);
        Log.d(TAG, "意图识别: " + result);

        switch (result.intent) {
            case NAVIGATE:
                if (result.destination != null) {
                    destinationName = result.destination;
                    hasDestination = true;
                    speak(L("正在导航到" + result.destination,
                            "Navigating to " + result.destination), speechSpeed);
                    startNavigation(result.destination);
                } else {
                    speak(L("请说出目的地", "Please say the destination"), speechSpeed);
                }
                break;
            case SET_LOCATION:
                if (result.destination == null) break;
                if (!isLocated || currentPosition == null) {
                    speak(L("正在定位到" + result.destination,
                            "Locating to " + result.destination), speechSpeed);
                    updateDisplay(L("定位到 " + result.destination + "…",
                            "Locating to " + result.destination + "..."));
                }
                Position pos = findPositionByNameOrCache(result.destination);
                if (pos != null) {
                    currentPosition = pos;
                    isLocated = true;
                    navigationService.setCurrentPosition(pos);
                    speak(L("已定位到" + pos.getLabel(),
                            "Located at " + pos.getLabel()), speechSpeed);
                    updateDisplay(L("当前位置：" + pos.getLabel(),
                            "Location: " + pos.getLabel()));
                } else {
                    addToUnmatchedLocationCache(result.destination);
                    speak(L("未找到位置" + result.destination + "，已缓存，正在尝试WiFi定位",
                            "Location " + result.destination + " not found, cached, trying WiFi positioning"), speechSpeed);
                    updateDisplay(L("未找到「" + result.destination + "」，已缓存，正在定位…",
                            "\"" + result.destination + "\" not found, cached, locating..."));
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
                speak(currentPosition != null
                        ? L("您在" + currentPosition.getLabel(),
                        "You are at " + currentPosition.getLabel())
                        : L("位置未知", "Location unknown"), speechSpeed);
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
                    speak(L("导航进行中，请继续前进",
                            "Navigation in progress, please continue"), speechSpeed);
                } else {
                    speak(L("当前没有进行导航",
                            "No active navigation"), speechSpeed);
                }
                break;
            case START_NAVIGATION:
                if (hasDestination && currentPosition != null) {
                    startNavigation(destinationName);
                } else {
                    speak(L("请先设置目的地并定位",
                            "Please set destination and locate first"), speechSpeed);
                }
                break;
            case ENTER_SETTINGS:
                enterSettingsMode();
                break;
            case EXIT_SETTINGS:
                if (isInSettingsMode) {
                    exitSettingsMode();
                } else {
                    speak(L("当前不在设置模式",
                            "Not in settings mode"), speechSpeed);
                }
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
                if (hasDestination && currentPosition != null) {
                    startNavigation(destinationName);
                } else {
                    speak(L("请先设置目的地并定位",
                            "Please set destination and locate first"), speechSpeed);
                }
                break;
            case FLOOR_UP:
                speak(L("请使用楼梯或电梯上楼",
                        "Please take the stairs or elevator up"), speechSpeed);
                break;
            case FLOOR_DOWN:
                speak(L("请使用楼梯或电梯下楼",
                        "Please take the stairs or elevator down"), speechSpeed);
                break;
            default:
                speak(L("未识别: " + command,
                        "Not recognized: " + command), speechSpeed);
        }
    }

    /**
     * 初始化所有服务
     */
    private void initServices() {
        Log.d(TAG, "初始化服务 - 使用工厂模式");

        serviceFactory = ServiceFactory.getInstance(this);
        ttsService = serviceFactory.getTtsService();

        ttsService.setTTSSpeechListener(new C_TextToSpeechService.TTSSpeechListener() {
            @Override
            public void onSpeechStart() {
                Log.d(TAG, "TTS开始播报");
                runOnUiThread(() -> {
                    startGlowEffect();
                    glowSafetyHandler.removeCallbacks(glowSafetyOff);
                    glowSafetyHandler.postDelayed(glowSafetyOff, GLOW_MAX_DURATION);
                });
            }

            @Override
            public void onSpeechDone() {
                Log.d(TAG, "TTS播报完成");
                lastTtsEndTime = System.currentTimeMillis();
                runOnUiThread(() -> {
                    glowSafetyHandler.removeCallbacks(glowSafetyOff);
                    stopGlowEffect();
                });
            }

            @Override
            public void onSpeechError(String errorMessage) {
                Log.d(TAG, "TTS播报出错: " + errorMessage);
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
                    Log.d(TAG, "Vosk识别结果: " + command);

                    long timeSinceTtsEnd = System.currentTimeMillis() - lastTtsEndTime;
                    if (timeSinceTtsEnd < TTS_END_ECHO_WINDOW_MS) {
                        Log.d(TAG, "TTS结束后" + timeSinceTtsEnd + "ms内的识别，忽略可能是回声: " + command);
                        return;
                    }

                    runOnUiThread(() -> {
                        lastRecognizedText = command;
                        updateDisplay(L("识别: " + command,
                                "Recognized: " + command));
                        vibrate(30);
                        processVoiceCommand(command);
                    });
                }
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "Vosk识别错误: " + errorMsg);
                long timeSinceTtsEnd = System.currentTimeMillis() - lastTtsEndTime;
                if (timeSinceTtsEnd < TTS_END_ECHO_WINDOW_MS) {
                    return;
                }
                runOnUiThread(() -> speak(L("识别失败，请重试",
                        "Recognition failed, please try again"), speechSpeed));
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

        navigationService.setPositionUpdateCallback(newPosition -> {
            runOnUiThread(() -> currentPosition = newPosition);
        });

        navigationService.setNavigationEventCallback(new CompassEnhancedNavigationService.NavigationEventCallback() {
            @Override
            public void onNavigationStarted(String from, String to, int totalSteps, double totalDistance, int estimatedSeconds) {
                runOnUiThread(() -> {
                    updateDisplay(L(
                            String.format("导航开始：%s → %s", from, to),
                            String.format("Navigation started: %s → %s", from, to)));
                    vibrate(200);
                });
            }

            @Override
            public void onStepAnnounced(int stepIndex, int totalSteps, String instruction, String absoluteDirection) {
                lastNavigationInstruction = instruction;
                runOnUiThread(() -> updateDisplay(String.format("[%d/%d] %s", stepIndex, totalSteps, instruction)));
            }

            @Override
            public void onTurnWarning(String t, String a, int s) {
                runOnUiThread(() -> vibrate(100));
            }

            @Override
            public void onProgressUpdate(int c, int r, double d) {
            }

            @Override
            public void onArrival(String destination, String detailInfo) {
                runOnUiThread(() -> {
                    hasDestination = false;
                    destinationName = "";
                    updateDisplay(L("已到达：" + destination,
                            "Arrived: " + destination));
                    vibrate(500);
                    if (pathStorage != null && currentPosition != null) {
                        pathStorage.recordRoute(currentPosition.getLabel(), destination);
                    }
                });
            }

            @Override
            public void onNavigationStopped(boolean reachedDestination) {
                runOnUiThread(() -> {
                    if (!reachedDestination) updateDisplay(L("导航已停止", "Navigation stopped"));
                });
            }

            @Override
            public void onOffRoute(double deviationMeters) {
                runOnUiThread(() -> {
                    vibrate(300);
                    boolean useCm = isUsingCm();
                    float strideCm = getStrideCm();

                    if (useCm) {
                        double deviationCm = deviationMeters * 100.0;
                        speak(L(
                                String.format(Locale.CHINA, "偏离路线%.0f厘米", deviationCm),
                                String.format(Locale.US, "Off route by %.0f cm", deviationCm)), speechSpeed);
                    } else {
                        double deviationSteps = (deviationMeters * 100.0) / strideCm;
                        speak(L(
                                String.format(Locale.CHINA, "偏离路线约%.0f步", deviationSteps),
                                String.format(Locale.US, "Off route by about %.0f steps", deviationSteps)), speechSpeed);
                    }
                });
            }

            @Override
            public void onLocationUpdated(Position position) {
                runOnUiThread(() -> currentPosition = position);
            }

            @Override
            public void onDirectionUpdated(float heading, String cardinal) {
            }
        });

        pathStorage = new PathStorageService(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        statusUpdateHandler.postDelayed(this::checkServicesStatus, 4000);
    }

    /**
     * 检查TTS、Vosk服务状态并播报
     */
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
        speak(L("语音系统初始化完成", "Voice system initialization complete"), speechSpeed);
    }

    /**
     * 根据名称查找位置（精确匹配+模糊匹配）
     */
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

    /**
     * 匹配位置：先查路径数据，再查未匹配位置缓存；缓存命中则构造Position（楼层为0）
     */
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
                Log.d(TAG, "从缓存匹配位置: " + cached);
                return fromCache;
            }
        }
        return null;
    }

    /**
     * 从SharedPreferences加载未匹配位置缓存
     */
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

    /**
     * 将未匹配位置缓存写入SharedPreferences（持久化）
     */
    private void saveUnmatchedLocationCache() {
        SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
        prefs.edit().putString(PREF_UNMATCHED_LOCATION_CACHE, String.join(",", unmatchedLocationCache)).apply();
    }

    /**
     * 将匹配不到的位置名称加入缓存并持久化
     */
    private void addToUnmatchedLocationCache(String locationName) {
        if (locationName == null || locationName.trim().isEmpty()) return;
        String name = locationName.trim();
        unmatchedLocationCache.add(name);
        saveUnmatchedLocationCache();
        Log.d(TAG, "未匹配位置已缓存: " + name + ", 缓存数量: " + unmatchedLocationCache.size());
    }

    /**
     * 初始化所有控件
     */
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
        tvStrideDisplay = findViewById(R.id.tv_stride_display);
    }

    /**
     * 初始化所有监听器
     */
    private void initListeners() {
        tvTopDisplay.setOnClickListener(v -> {
            String toSpeak = !lastNavigationInstruction.isEmpty() ? lastNavigationInstruction : lastSpokenText;
            if (!toSpeak.isEmpty()) {
                speak(toSpeak, speechSpeed);
                vibrate(50);
            }
        });

        setupLocateNavButton();

        btnVoiceAssistant.setOnTouchListener((v, event) -> {
            if (isInSettingsMode) {
                speak(L("请先退出设置模式", "Please exit settings mode first"), speechSpeed);
                return true;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isVoiceButtonPressed = true;
                    handleVoiceButtonPress();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
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

    /**
     * 初始化定位/导航按钮的长按+单击逻辑
     */
    private void setupLocateNavButton() {
        btnLocateNav.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isLongPressTriggered = false;
                    longPressRunnable = () -> {
                        isLongPressTriggered = true;
                        onLongPressDetected();
                    };
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

    /**
     * 定位/导航按钮单击逻辑
     */
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

    /**
     * 更新当前位置（导航中调用）
     */
    private void updateCurrentLocation() {
        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                runOnUiThread(() -> {
                    currentPosition = position;
                    if (navigationService.isNavigating()) {
                        navigationService.setCurrentPosition(position);
                    }
                    speak(L("当前位置：" + position.getLabel(),
                            "Current location: " + position.getLabel()), speechSpeed);
                    updateDisplay(L("当前位置：" + position.getLabel(),
                            "Current location: " + position.getLabel()));
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> speak(L("定位更新失败",
                        "Location update failed"), speechSpeed));
            }
        });
    }

    /**
     * 定位/导航按钮长按逻辑
     */
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
                speak(L("开始导航到" + destinationName,
                        "Starting navigation to " + destinationName), speechSpeed);
                startNavigation(destinationName);
            } else if (!hasDestination) {
                if (currentPosition != null) {
                    announceCurrentEnvironment();
                } else {
                    speak(L("请先定位或设置目的地",
                            "Please locate or set a destination first"), speechSpeed);
                }
            } else {
                speak(L("正在为您定位", "Locating for you"), speechSpeed);
                startLocation();
            }
        }
    }

    /**
     * 播报当前环境（位置+方向+附近地点+推荐目的地）
     */
    private void announceCurrentEnvironment() {
        if (currentPosition == null) return;
        String msg = L("当前在" + currentPosition.getLabel() + "。" + navigationService.getCurrentDirectionInfo(),
                "Currently at " + currentPosition.getLabel() + ". " + navigationService.getCurrentDirectionInfo());
        speak(msg, speechSpeed);
        updateDisplay(L("当前：" + currentPosition.getLabel(),
                "Current: " + currentPosition.getLabel()));

        statusUpdateHandler.postDelayed(() -> {
            announceNearbyPOIs(currentPosition);
            if (!hasDestination) {
                statusUpdateHandler.postDelayed(() -> {
                    List<String> recs = pathStorage.recommendDestinations(currentPosition.getLabel(), 3);
                    speak(!recs.isEmpty()
                            ? L("推荐目的地：" + String.join("、", recs),
                            "Recommended destinations: " + String.join(", ", recs))
                            : L("请说出目的地", "Please say the destination"), speechSpeed);
                }, 3000);
            }
        }, 2000);
    }

    /**
     * 设置页面手势监听器
     */
    private void setupSettingsGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (!isInSettingsMode || e1 == null || e2 == null) return false;

                float dX = e2.getX() - e1.getX();
                float dY = e2.getY() - e1.getY();

                View paceView = findViewById(R.id.tv_pace_display);
                View unitView = findViewById(R.id.tv_unit_display);
                View strideView = findViewById(R.id.tv_stride_display);

                float startY = e1.getY();

                if (strideView != null) {
                    int[] loc = new int[2];
                    strideView.getLocationOnScreen(loc);
                    int top = loc[1];
                    int bottom = top + strideView.getHeight();

                    if (startY >= top && startY <= bottom && Math.abs(dY) > Math.abs(dX) && Math.abs(dY) > 100) {
                        adjustStrideCm(dY < 0 ? 5f : -5f);
                        return true;
                    }
                }

                if (paceView != null) {
                    int[] loc = new int[2];
                    paceView.getLocationOnScreen(loc);
                    int top = loc[1];
                    int bottom = top + paceView.getHeight();

                    if (startY >= top && startY <= bottom && Math.abs(dX) > Math.abs(dY) && Math.abs(dX) > 100) {
                        switchPace();
                        return true;
                    }
                }

                if (unitView != null) {
                    int[] loc = new int[2];
                    unitView.getLocationOnScreen(loc);
                    int top = loc[1];
                    int bottom = top + unitView.getHeight();

                    if (startY >= top && startY <= bottom && Math.abs(dX) > Math.abs(dY) && Math.abs(dX) > 100) {
                        switchDistanceUnit();
                        return true;
                    }
                }

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

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isInSettingsMode) {
                    exitSettingsMode();
                    return true;
                }
                return false;
            }
        });

        settingsFullscreen.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    /**
     * 开始定位（初始化定位流程）
     */
    private void startLocation() {
        speak(L("正在定位", "Locating"), speechSpeed);
        vibrate(100);
        updateDisplay(L("定位中...", "Locating..."));
        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                runOnUiThread(() -> {
                    currentPosition = position;
                    isLocated = true;
                    navigationService.setCurrentPosition(position);
                    speak(L("定位成功，当前在" + position.getLabel(),
                            "Located successfully, you are at " + position.getLabel()), speechSpeed);
                    vibrate(200);
                    statusUpdateHandler.postDelayed(() -> {
                        announceNearbyPOIs(position);
                        if (hasDestination) {
                            statusUpdateHandler.postDelayed(() -> speak(L(
                                            "目的地" + destinationName + "，长按开始导航",
                                            "Destination " + destinationName + ", long press to start navigation"),
                                    speechSpeed), 2000);
                        }
                    }, 2000);
                });
            }

            @Override
            public void onFailure(String e) {
                runOnUiThread(() -> {
                    isLocated = false;
                    speak(L("WiFi定位失败，请说我在加位置名称手动设置，例如我在门口",
                                    "WiFi positioning failed. Say \"I am at\" plus a location name to set manually, e.g. \"I am at entrance\""),
                            speechSpeed);
                    updateDisplay(L("定位失败 | 说\"我在XX\"设置位置",
                            "Location failed | Say \"I am at XX\" to set location"));
                });
            }
        });
    }

    /**
     * 播报当前位置附近的POI（最多3个）
     */
    private void announceNearbyPOIs(Position position) {
        List<PathEntity> allPaths = PathParser.getAllPaths();
        List<String> nearby = new ArrayList<>();
        for (PathEntity path : allPaths) {
            if (path.getStartLabel_cn().equals(position.getLabel())) {
                nearby.add(path.getEndLabel_cn());
            }
        }
        if (!nearby.isEmpty()) {
            List<String> top = nearby.subList(0, Math.min(3, nearby.size()));
            speak(L("附近有：" + String.join("、", top),
                    "Nearby: " + String.join(", ", top)), speechSpeed);
        }
    }

    /**
     * 开始导航
     */
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
            speak(L("未找到目的地：" + target,
                    "Destination not found: " + target), speechSpeed);
            updateDisplay(L("目的地不存在：" + target,
                    "Destination not found: " + target));
            hasDestination = false;
            destinationName = "";
            return;
        }

        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        navigationService.setNavigationConfig(navigationPace, speechSpeed, currentLanguage.locale);

        List<PathEntity> path = navigationService.calculatePath();
        if (path == null || path.isEmpty()) {
            speak(L("未找到路径到" + target,
                    "No route found to " + target), speechSpeed);
            hasDestination = false;
            destinationName = "";
            return;
        }

        speak(L("开始导航到" + target + "，预计" + path.size() + "个步骤",
                        "Starting navigation to " + target + ", approximately " + path.size() + " steps"),
                speechSpeed);
        updateDisplay(L("导航中 → " + target,
                "Navigating → " + target));
        navigationService.startContinuousNavigation();
    }

    /**
     * 启动导航状态更新
     */
    private void startStatusUpdater() {
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNavigationStatus();
                statusUpdateHandler.postDelayed(this, 2000);
            }
        };
        statusUpdateHandler.post(statusUpdateRunnable);
    }

    /**
     * 更新导航状态显示
     */
    private void updateNavigationStatus() {
        if (isInSettingsMode) return;

        String status;
        if (navigationService.isNavigating()) {
            status = L("导航中 → ", "Navigating → ") + destinationName;
            if (currentPosition != null) {
                status += L(" | 当前：", " | Current: ") + currentPosition.getLabel();
            }
        } else if (hasDestination) {
            status = L("已设目的地：", "Destination: ") + destinationName;
            if (currentPosition != null) {
                status += L(" | 长按开始导航", " | Long press to start navigation");
            } else {
                status += L(" | 请先定位", " | Please locate first");
            }
        } else {
            status = currentPosition != null ?
                    L("当前位置：" + currentPosition.getLabel() + " | 长按查看环境",
                            "Current: " + currentPosition.getLabel() + " | Long press for environment") :
                    L("单击定位 | 长按设置目的地",
                            "Tap to locate | Long press to set destination");
        }

        updateDisplay(status);
    }

    /**
     * 进入设置模式
     */
    private void enterSettingsMode() {
        isInSettingsMode = true;
        settingsFullscreen.setVisibility(View.VISIBLE);
        updateSettingsDisplay();

        speak(L(
                "进入设置。上下滑动调语速，左右滑动切换语言，左右滑动间隔区域切换间隔，左右滑动单位区域切换步数或厘米，上下滑动步幅区域调节步幅，双击退出",
                "Entering settings. Swipe up or down to adjust speech speed, swipe left or right to switch language, swipe the interval area to change interval, swipe the unit area to switch steps or cm, swipe the stride area up or down to adjust stride, double tap to exit"
        ), speechSpeed);
    }

    /**
     * 退出设置模式
     */
    private void exitSettingsMode() {
        isInSettingsMode = false;
        settingsFullscreen.setVisibility(View.GONE);
        updateUnitDisplay();

        boolean useCm = isUsingCm();
        float strideCm = getStrideCm();

        speak(L(
                String.format(Locale.CHINA, "设置完成。语速%.1f倍，%s，单位%s，步幅%.0f厘米",
                        speechSpeed,
                        currentLanguage.displayName,
                        useCm ? "厘米" : "步数",
                        strideCm),
                String.format(Locale.US, "Settings saved. Speech speed %.1fx, %s, unit %s, stride %.0f cm",
                        speechSpeed,
                        currentLanguage.displayName,
                        useCm ? "cm" : "steps",
                        strideCm)
        ), speechSpeed);
    }

    /**
     * 更新设置界面显示（语速、语言、间隔、单位、步幅）
     */
    private void updateSettingsDisplay() {
        if (tvSpeedDisplay != null) {
            tvSpeedDisplay.setText(L(
                    String.format(Locale.CHINA, "语速：%.1f倍", speechSpeed),
                    String.format(Locale.US, "Speed: %.1fx", speechSpeed)));
        }

        if (tvLanguageDisplay != null) {
            tvLanguageDisplay.setText(L("语言：", "Language: ") + currentLanguage.displayName);
        }

        if (tvPaceDisplay != null) {
            tvPaceDisplay.setText(L(
                    String.format(Locale.CHINA, "间隔：%d秒", navigationPace / 1000),
                    String.format(Locale.US, "Interval: %ds", navigationPace / 1000)));
        }

        boolean useCm = isUsingCm();
        float strideCm = getStrideCm();

        if (tvUnitDisplay != null) {
            tvUnitDisplay.setText(L(
                    "距离单位：" + (useCm ? "厘米" : "步数"),
                    "Distance Unit: " + (useCm ? "cm" : "steps")));
        }

        if (tvStrideDisplay != null) {
            tvStrideDisplay.setText(L(
                    String.format(Locale.CHINA, "步幅：%.0f厘米", strideCm),
                    String.format(Locale.US, "Stride: %.0f cm", strideCm)));
        }
    }

    /**
     * 调整TTS播报语速（限制范围0.5-2.0倍）
     */
    private void adjustSpeed(float delta) {
        speechSpeed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speechSpeed + delta));
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setSpeed(speechSpeed);
        }

        getSharedPreferences("UserSettings", MODE_PRIVATE)
                .edit()
                .putFloat("speechRate", speechSpeed)
                .apply();

        updateSettingsDisplay();
        speak(L(
                        String.format("语速%.1f倍", speechSpeed),
                        String.format(Locale.US, "Speech speed %.1fx", speechSpeed)),
                speechSpeed);
    }

    /**
     * 调整步幅（厘米）
     */
    private void adjustStrideCm(float deltaCm) {
        float newStride = Math.max(30f, Math.min(120f, getStrideCm() + deltaCm));
        getSharedPreferences("UserSettings", MODE_PRIVATE)
                .edit()
                .putFloat("strideCm", newStride)
                .apply();

        updateSettingsDisplay();

        speak(L(
                String.format(Locale.CHINA, "步幅%.0f厘米", newStride),
                String.format(Locale.US, "Stride %.0f cm", newStride)
        ), speechSpeed);
    }

    /**
     * 切换距离单位（步数/厘米）
     */
    private void switchDistanceUnit() {
        boolean newUseCm = !isUsingCm();
        getSharedPreferences("UserSettings", MODE_PRIVATE)
                .edit()
                .putBoolean("useCm", newUseCm)
                .apply();

        updateSettingsDisplay();

        speak(L(
                "距离单位已切换为" + (newUseCm ? "厘米" : "步数"),
                "Distance unit switched to " + (newUseCm ? "cm" : "steps")
        ), speechSpeed);
    }

    /**
     * 切换语言（中文/英文/粤语）
     */
    private void switchLanguage() {
        VoskSpeechRecognizerService.Language[] langs = VoskSpeechRecognizerService.Language.values();
        int idx = 0;
        for (int i = 0; i < langs.length; i++) {
            if (langs[i] == currentLanguage) {
                idx = i;
                break;
            }
        }
        switchToLanguage(langs[(idx + 1) % langs.length]);
    }

    private boolean isSwitchingLanguage = false;

    /**
     * 切换到指定语言
     */
    private void switchToLanguage(VoskSpeechRecognizerService.Language language) {
        if (isSwitchingLanguage) {
            Log.d(TAG, "正在切换语言中，跳过重复调用");
            return;
        }

        if (currentLanguage == language) {
            Log.d(TAG, "已经是" + language.displayName + "，无需切换");
            return;
        }

        isSwitchingLanguage = true;
        currentLanguage = language;

        if (ttsService != null && ttsService.isReady()) {
            ttsService.setLanguage(language.locale);
        }

        try {
            if (serviceFactory != null) {
                serviceFactory.switchLanguage(language);
            }
        } catch (Exception e) {
            Log.e(TAG, "Vosk切换失败，但不影响UI: " + e.getMessage());
        }

        String msg = (language == VoskSpeechRecognizerService.Language.ENGLISH)
                ? "Switched to " + language.displayName
                : "已切换到" + language.displayName;
        if (ttsService != null && ttsService.isReady()) {
            ttsService.speak(msg, speechSpeed);
        }

        statusUpdateHandler.postDelayed(() -> {
            updateAppLocale(language.locale);
            isSwitchingLanguage = false;
        }, 1500);
    }

    /**
     * 更新APP全局语言并重建Activity
     */
    private void updateAppLocale(Locale locale) {
        getSharedPreferences("UserSettings", MODE_PRIVATE)
                .edit()
                .putString("app_locale", locale.toLanguageTag())
                .putBoolean("in_settings_mode", isInSettingsMode)
                .apply();

        android.content.res.Resources res = getResources();
        android.content.res.Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());

        recreate();
    }

    /**
     * 应用语言设置（不重建Activity，用于初始化时生效）
     */
    private void applyLocaleWithoutRecreate(Locale locale) {
        android.content.res.Resources res = getResources();
        android.content.res.Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    /**
     * 切换导航间隔（2/3/5/8秒）
     */
    private void switchPace() {
        paceIndex = (paceIndex + 1) % PACE_OPTIONS.length;
        navigationPace = PACE_OPTIONS[paceIndex];
        updateSettingsDisplay();
        speak(L(
                        String.format("间隔%d秒", navigationPace / 1000),
                        String.format(Locale.US, "Interval %d seconds", navigationPace / 1000)),
                speechSpeed);
    }

    /**
     * 更新顶部显示文本
     */
    private void updateDisplay(String text) {
        if (tvTopDisplay != null) {
            tvTopDisplay.setText(text);
        }
    }

    /**
     * 清理语音识别结果
     */
    private String cleanRecognizedText(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        cleaned = cleaned.replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("[\\n\\r\\t]", " ");
        cleaned = cleaned.replaceAll("[　]+", "").replaceAll("[ \\u00A0\\u1680\\u180E\\u2000-\\u200B\\u202F\\u205F\\u3000\\uFEFF]+", " ");
        return cleaned.trim();
    }

    /**
     * TTS播报
     */
    private void speak(String text, float speed) {
        lastSpokenText = text;

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

    /**
     * 震动反馈
     */
    private void vibrate(long ms) {
        if (vibrator != null) {
            vibrator.vibrate(ms);
        }
    }

    /**
     * 启动绿色呼吸灯光晕效果
     */
    private void startGlowEffect() {
        if (viewGlowOverlay == null) return;
        viewGlowOverlay.startGlow();
    }

    /**
     * 停止绿色呼吸灯光晕效果
     */
    private void stopGlowEffect() {
        if (viewGlowOverlay == null) return;
        viewGlowOverlay.stopGlow();
    }

    /**
     * 处理语音按钮按下事件
     */
    private void handleVoiceButtonPress() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                speak(L("需要录音权限", "Recording permission required"), speechSpeed);
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 100);
                isVoiceButtonPressed = false;
                return;
            }
        }

        if (voskService != null && voskService.isInitialized()) {
            vibrate(80);

            btnVoiceAssistant.setScaleX(0.95f);
            btnVoiceAssistant.setScaleY(0.95f);

            Log.d(TAG, "设置按钮背景为鲜艳绿色（按住效果）");
            btnVoiceAssistant.post(() -> {
                btnVoiceAssistant.setBackgroundTintList(null);
                btnVoiceAssistant.setBackgroundResource(android.R.drawable.btn_default);
                Log.d(TAG, "背景已设置，tint已清除");
            });

            isVoiceRecording = true;
            voskService.startListening();
            updateDisplay(L("正在聆听...", "Listening..."));
            startGlowEffect();

            Log.d(TAG, "语音按钮按下，开始录音");
        } else {
            String input = etVoiceSimulate.getText().toString().trim();
            if (!input.isEmpty()) {
                processVoiceCommand(input);
                etVoiceSimulate.setText("");
            } else {
                speak(L("语音识别尚未就绪，请在输入框输入指令",
                        "Voice recognition not ready yet, please type in the input box"), speechSpeed);
            }
            isVoiceButtonPressed = false;
        }
    }

    /**
     * 处理语音按钮释放事件
     */
    private void handleVoiceButtonRelease() {
        btnVoiceAssistant.setScaleX(1.0f);
        btnVoiceAssistant.setScaleY(1.0f);

        Log.d(TAG, "恢复按钮背景");
        btnVoiceAssistant.post(() -> {
            btnVoiceAssistant.setBackgroundTintList(null);
            btnVoiceAssistant.setBackgroundResource(R.drawable.button_secondary);
        });

        if (isVoiceRecording) {
            isVoiceRecording = false;

            if (voskService != null) {
                voskService.stopListening();
            }
            stopGlowEffect();

            vibrate(50);
            updateDisplay(L("识别中...", "Recognizing..."));

            Log.d(TAG, "语音按钮释放，停止录音，等待识别结果");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopGlowEffect();
        statusUpdateHandler.removeCallbacksAndMessages(null);
        longPressHandler.removeCallbacksAndMessages(null);
        glowSafetyHandler.removeCallbacksAndMessages(null);

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
