package com.example.indoornavblind.ui.activities;

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

import com.example.indoornavblind.R;
import com.example.indoornavblind.database.AppDatabase;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.database.entity.NavigationNodeEntity;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.C_SpeechRecognizerService;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService;
import com.example.indoornavblind.service.PathStorageService;
import com.example.indoornavblind.service.impl.CompassEnhancedNavigationService;
import com.example.indoornavblind.service.impl.L_KnnLocationService;
import com.example.indoornavblind.service.L_WiFiScannerServiceImpl;
import com.example.indoornavblind.service.impl.LocalIntentEngine;
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
    private C_TextToSpeechService ttsService;      // TTS播报
    private VoskSpeechRecognizerService voskService; // 语音识别
    private CompassEnhancedNavigationService navigationService; // 导航
    private LocationService locationService;
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
    private String lastNavigationInstruction = ""; // 新增
    private String lastSpokenText = "";
    private String lastRecognizedText = ""; // 最后识别的Vosk内容

    // TTS回声保护机制
    private long lastTtsStartTime = 0;
    private long lastTtsEndTime = 0;
    private static final long TTS_ECHO_WINDOW_MS = 3000; // TTS开始后3秒内的识别结果视为回声
    private static final long TTS_END_ECHO_WINDOW_MS = 3000; // TTS结束后3秒内的识别结果也视为回声（增加延迟）
    private static final long VOSK_RECOVERY_DELAY = 300; // Vosk恢复延迟300ms

    private LocalIntentEngine intentEngine;

    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean isLongPressTriggered = false;
    private Handler statusUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdateRunnable;

    private GestureDetector gestureDetector;
    private VoskSpeechRecognizerService.OnRecognitionListener voskServiceListener;  // 保存原有监听器
    private static final float SPEED_STEP = 0.1f;
    private static final float SPEED_MIN = 0.5f;
    private static final float SPEED_MAX = 2.0f;
    private static final int[] PACE_OPTIONS = {2000, 3000, 5000, 8000};
    private int paceIndex = 2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        // 恢复语言设置
        android.content.SharedPreferences prefs = getSharedPreferences("UserSettings", MODE_PRIVATE);
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

        setContentView(R.layout.activity_main);

        android.content.SharedPreferences prefs2 = getSharedPreferences("UserSettings", MODE_PRIVATE);
        boolean wasInSettings = prefs2.getBoolean("in_settings_mode", false);
        if (wasInSettings) {
            prefs2.edit().putBoolean("in_settings_mode", false).apply(); // 清除标记
        }

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);

        initServices();
        initViews();
        initListeners();

        if (wasInSettings) {
            isInSettingsMode = true;
            settingsFullscreen.setVisibility(View.VISIBLE);
            updateSettingsDisplay();
        }


        new Thread(() -> {
            AppDatabase database = AppDatabase.getInstance();
            NavigationNodeDao dao = database.navigationNodeDao();

            // 检查是否已有数据
            List<NavigationNodeEntity> existingData = dao.getAllNodes();
            if (existingData == null || existingData.isEmpty()) {
                NavigationDataInitializer.initializeSampleData(dao);
                Log.d("MainActivity", "Navigation data initialized");
            }
        }).start();
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
            case ENTER_SETTINGS:
                enterSettingsMode();
                break;
            case EXIT_SETTINGS:
                if (isInSettingsMode) {
                    exitSettingsMode();
                } else {
                    speak("当前不在设置模式", speechSpeed);
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
                    speak("请先设置目的地并定位", speechSpeed);
                }
                break;
            case FLOOR_UP:
                speak("请使用楼梯或电梯上楼", speechSpeed);
                break;
            case FLOOR_DOWN:
                speak("请使用楼梯或电梯下楼", speechSpeed);
                break;
            default:
                speak("未识别: " + command, speechSpeed);
        }
    }

    private void initServices() {
        Log.d(TAG, "初始化服务 - 使用工厂模式");

        // 1. 初始化服务工厂（核心）
        serviceFactory = ServiceFactory.getInstance(this);

        // 2. 获取TTS服务（语音播报）
        ttsService = serviceFactory.getTtsService();

        // 2.1 设置TTS状态监听器，用于控制Vosk监听（防止回声识别）
        ttsService.setTTSSpeechListener(new C_TextToSpeechService.TTSSpeechListener() {
            @Override
            public void onSpeechStart() {
                Log.d(TAG, "TTS开始播报，停止Vosk监听");
                // 记录TTS开始时间，用于回声保护
                lastTtsStartTime = System.currentTimeMillis();
                // 在UI线程中执行：先设置TTS状态，再停止Vosk
                runOnUiThread(() -> {
                    if (voskService != null) {
                        // 先设置TTS播报状态标志
                        voskService.setTtsSpeaking(true);
                        // 无论是否正在监听，都调用pauseForTTS确保Vosk完全停止
                        // 因为isListening可能在Vosk返回结果后已被设为false，但服务仍在运行
                        voskService.pauseForTTS();
                        Log.d(TAG, "Vosk已完全停止（TTS播报中）");
                    }
                });
            }

            @Override
            public void onSpeechDone() {
                Log.d(TAG, "TTS播报完成");
                // 记录TTS结束时间，用于回声保护
                lastTtsEndTime = System.currentTimeMillis();
                // 在UI线程中执行：先重置TTS状态，再延迟恢复Vosk
                runOnUiThread(() -> {
                    if (voskService != null) {
                        voskService.setTtsSpeaking(false);
                        Log.d(TAG, "TTS状态已重置，isSpeaking=" + ttsService.isSpeaking() + ", queueSize=" + ttsService.getQueueSize());
                    }
                    // 延迟恢复Vosk监听
                    Log.d(TAG, "安排" + VOSK_RECOVERY_DELAY + "ms后重启Vosk");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Log.d(TAG, "Vosk重启定时器触发，voskService=" + (voskService != null ? "非空" : "null") +
                            ", isInSettingsMode=" + isInSettingsMode +
                            ", isSpeaking=" + (ttsService != null ? ttsService.isSpeaking() : "null") +
                            ", queueSize=" + (ttsService != null ? ttsService.getQueueSize() : "null"));
                        runOnUiThread(() -> {
                            if (voskService != null) {
                                voskService.resumeAfterTTS();
                                Log.d(TAG, "Vosk监听已恢复（isInSettingsMode=" + isInSettingsMode + "）");
                            } else {
                                Log.d(TAG, "Vosk未恢复: voskService=null");
                            }
                        });
                    }, VOSK_RECOVERY_DELAY);
                });
            }

            @Override
            public void onSpeechError(String errorMessage) {
                Log.d(TAG, "TTS播报出错，恢复Vosk监听");
                // 记录TTS结束时间，用于回声保护
                lastTtsEndTime = System.currentTimeMillis();
                // 在UI线程中执行：先重置TTS状态，再恢复Vosk
                runOnUiThread(() -> {
                    if (voskService != null) {
                        voskService.setTtsSpeaking(false);
                        if (!isInSettingsMode) {
                            voskService.resumeAfterTTS();
                            Log.d(TAG, "Vosk监听已恢复（TTS错误后）");
                        }
                    }
                });
            }
        });

        // 3. 获取Vosk服务（语音识别）
        voskService = serviceFactory.getVoskService();

        // 4. 设置Vosk识别监听器（直接处理语音指令）
        voskServiceListener = new VoskSpeechRecognizerService.OnRecognitionListener() {

            @Override
            public void onResult(ArrayList<String> results) {
                if (results != null && !results.isEmpty()) {
                    String command = cleanRecognizedText(results.get(0));
                    Log.d(TAG, "Vosk识别结果: " + command);

                    // 关键修复：检查TTS是否正在播报，避免回声识别
                    if (ttsService != null && ttsService.isSpeaking()) {
                        Log.d(TAG, "TTS正在播报，忽略可能是回声的识别结果");
                        // 不处理这个结果，不重启监听（由TTS监听器控制）
                        return;
                    }

                    // 额外保护：检查是否在TTS开始后的回声窗口期内
                    long timeSinceTtsStart = System.currentTimeMillis() - lastTtsStartTime;
                    if (timeSinceTtsStart < TTS_ECHO_WINDOW_MS) {
                        Log.d(TAG, "TTS开始后" + timeSinceTtsStart + "ms内的识别，忽略可能是回声的识别结果: " + command);
                        return;
                    }

                    // 额外保护：检查是否在TTS结束后的回声窗口期内（处理延迟到达的识别结果）
                    long timeSinceTtsEnd = System.currentTimeMillis() - lastTtsEndTime;
                    if (timeSinceTtsEnd < TTS_END_ECHO_WINDOW_MS) {
                        Log.d(TAG, "TTS结束后" + timeSinceTtsEnd + "ms内的识别，忽略可能是回声的识别结果: " + command);
                        return;
                    }

                    runOnUiThread(() -> {
                        lastRecognizedText = command;
                        updateDisplay("你说: " + command);
                        processVoiceCommand(command);
                    });
                }

                // 注意：不再自动重启监听，完全由TTS监听器控制
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "Vosk识别错误: " + errorMsg);
                // 检查是否在TTS保护窗口期内
                if (ttsService != null && ttsService.isSpeaking()) {
                    return; // 静默忽略，不处理错误
                }
                long timeSinceTtsStart = System.currentTimeMillis() - lastTtsStartTime;
                long timeSinceTtsEnd = System.currentTimeMillis() - lastTtsEndTime;
                if (timeSinceTtsStart < TTS_ECHO_WINDOW_MS || timeSinceTtsEnd < TTS_END_ECHO_WINDOW_MS) {
                    return; // 静默忽略，不处理错误
                }
                runOnUiThread(() -> {
                    // 只有TTS未播报时才播报错误，避免TTS循环
                    if (ttsService != null && !ttsService.isSpeaking()) {
                        speak("识别失败，请重试", speechSpeed);
                    }
                });
                // 错误后也不重启监听，由TTS监听器控制
            }
        };
        voskService.setRecognitionListener(voskServiceListener);

        intentEngine = new LocalIntentEngine(this);

        // 5. 初始化WiFi和定位服务（和原来一样）
        wifiScanner = new L_WiFiScannerServiceImpl();
        wifiScanner.init(this);
        locationService = new L_KnnLocationService(wifiScanner);
        locationService.init(this);

        // 6. 初始化导航服务 - 传入ttsService而不是voiceService
        navigationService = new CompassEnhancedNavigationService(ttsService, locationService);
        navigationService.initSensors(this);
        navigationService.loadUserSettings(this);

        // 7. 设置导航回调（保持你的原有逻辑）
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

            // 第310-311行
            @Override
            public void onStepAnnounced(int stepIndex, int totalSteps, String instruction, String absoluteDirection) {
                lastNavigationInstruction = instruction; // 新增这行
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

        // 8. 其他服务
        pathStorage = new PathStorageService(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // 9. 延迟检查服务状态
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkServicesStatus();
        }, 4000);
    }

    // 检查服务状态的方法
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
                pos.setFloor(path.getFloor()); // 关键：设置楼层
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

            // 检查录音权限
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    speak("需要录音权限", speechSpeed);
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 100);
                    return;
                }
            }

            if (voskService != null && voskService.isInitialized()) {
                speak("请说出您的指令", speechSpeed);
                vibrate(100);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    voskService.startListening();
                    updateDisplay("正在聆听...");
                }, 800);
            } else if (serviceFactory != null) {
                // 备用：使用Google语音识别
//                C_SpeechRecognizerService googleSR = serviceFactory.createSpeechRecognizerService();
//                googleSR.init(this);
//                googleSR.setRecognitionListener(new C_SpeechRecognizerService.OnRecognitionListener() {
//                    @Override
//                    public void onResult(ArrayList<String> results) {
//                        if (results != null && !results.isEmpty()) {
//                            processVoiceCommand(results.get(0));
//                        }
//                        googleSR.destroy();
//                    }
//                    @Override
//                    public void onError(String errorMsg) {
//                        speak("识别失败：" + errorMsg, speechSpeed);
//                        googleSR.destroy();
//                    }
//                });
//                googleSR.startListening();
            } else {
                // 文本输入fallback
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
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:+85212345678"));
            startActivity(intent);
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
            // 长按：停止导航
            navigationService.stopNavigation();
            hasDestination = false;
            destinationName = "";
            speak("导航已停止", speechSpeed);
            updateDisplay("导航已停止");
        } else {
            // 长按：开始导航到目的地（如果有）
            if (hasDestination && currentPosition != null) {
                speak("开始导航到" + destinationName, speechSpeed);
                startNavigation(destinationName);
            } else if (!hasDestination) {
                // 没有目的地，播报当前位置信息
                if (currentPosition != null) {
                    announceCurrentEnvironment();
                } else {
                    speak("请先定位或设置目的地", speechSpeed);
                }
            } else {
                // 有目的地但没有定位
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

        // 检查目标位置是否存在
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
        updateSettingsDisplay();
        speak(String.format("语速%.1f倍", speechSpeed), speechSpeed);
    }

    private void switchLanguage() {
        VoskSpeechRecognizerService.Language[] langs = VoskSpeechRecognizerService.Language.values();
        int idx = 0;
        for (int i = 0; i < langs.length; i++) { if (langs[i] == currentLanguage) { idx = i; break; } }
        switchToLanguage(langs[(idx + 1) % langs.length]);
    }

    private boolean isSwitchingLanguage = false;

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

//        // 1. 先更新TTS（不依赖Vosk）
//        if (ttsService != null && ttsService.isReady()) {
//            ttsService.setLanguage(language.locale);
//            ttsService.setSpeed(speechSpeed);
//        }
        // 1. 先更新TTS（不依赖Vosk）
        if (ttsService != null && ttsService.isReady()) {
            ttsService.setLanguage(language.locale);
        }

        // 2. Vosk单独切换，失败不影响其他
        try {
            if (serviceFactory != null) {
                serviceFactory.switchLanguage(language);
            }
        } catch (Exception e) {
            Log.e(TAG, "Vosk切换失败，但不影响UI: " + e.getMessage());
        }

        // 3. 先播报再recreate（否则recreate后speak会丢失）
        String msg = "已切换到" + language.displayName;
        if (ttsService != null && ttsService.isReady()) {
            ttsService.speak(msg, speechSpeed);
        }

        // 4. 延迟更新UI，等播报完成
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateAppLocale(language.locale);
            isSwitchingLanguage = false;
        }, 1500);
    }

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

    private void applyLocaleWithoutRecreate(Locale locale) {
        android.content.res.Resources res = getResources();
        android.content.res.Configuration config = res.getConfiguration();
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    private void switchPace() {
        paceIndex = (paceIndex + 1) % PACE_OPTIONS.length;
        navigationPace = PACE_OPTIONS[paceIndex];
        updateSettingsDisplay();
        speak(String.format("间隔%d秒", navigationPace / 1000), speechSpeed);
    }

    private void updateDisplay(String text) { if (tvTopDisplay != null) tvTopDisplay.setText(text); }

    /**
     * 清理语音识别结果，去除文字间的间隔
     * 去除首尾空格、连续空格、空格换行等
     */
    private String cleanRecognizedText(String text) {
        if (text == null) return "";
        // 去除首尾空格
        String cleaned = text.trim();
        // 去除连续多个空格，替换为单个空格
        cleaned = cleaned.replaceAll("\\s+", " ");
        // 去除换行符等空白字符
        cleaned = cleaned.replaceAll("[\\n\\r\\t]", " ");
        // 去除特殊空格字符（全角空格等）
        cleaned = cleaned.replaceAll("[　]+", "").replaceAll("[ \u00A0\u1680\u180E\u2000-\u200B\u202F\u205F\u3000\uFEFF]+", " ");
        return cleaned.trim();
    }

    private void speak(String text, float speed) {
        lastSpokenText = text;
        // 不再覆盖tv_top_display的显示，让其保持显示Vosk识别的内容

        // 检查音频状态
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

    private void vibrate(long ms) { if (vibrator != null) vibrator.vibrate(ms); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statusUpdateHandler.removeCallbacksAndMessages(null);
        longPressHandler.removeCallbacksAndMessages(null);

        if (navigationService != null) {
            navigationService.stopNavigation();
        }

        // serviceFactory.shutdown() 内部会处理TTS，但可能已dead，用try包裹
        try {
            if (serviceFactory != null) {
                serviceFactory.shutdown();
            }
        } catch (Exception e) {
            Log.w(TAG, "关闭服务异常: " + e.getMessage());
        }
    }
}