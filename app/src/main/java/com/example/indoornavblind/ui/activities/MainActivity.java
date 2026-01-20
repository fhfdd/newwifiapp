package com.example.indoornavblind.ui.activities;

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
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService;
import com.example.indoornavblind.service.PathStorageService;
import com.example.indoornavblind.service.impl.CompassEnhancedNavigationService;
import com.example.indoornavblind.service.impl.L_KnnLocationService;
import com.example.indoornavblind.service.L_WiFiScannerServiceImpl;
import com.example.indoornavblind.util.NavigationDataInitializer;
import com.example.indoornavblind.util.PathParser;
import com.example.indoornavblind.util.PermissionUtil;
import com.example.indoornavblind.factory.ServiceFactory;
import com.example.indoornavblind.service.C_TextToSpeechService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            speak("欢迎使用完全离线盲人室内导航系统。单击按钮开始定位", speechSpeed);
        }, 1000);

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

    private void processVoiceCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }

        String cmd = command.toLowerCase().trim();
        Log.d(TAG, "处理语音命令: " + cmd);

        // 1. 导航命令
        if (cmd.contains("去") || cmd.contains("导航到") || cmd.contains("到") ||
                cmd.contains("go to") || cmd.contains("navigate to")) {

            // 提取目的地（简单逻辑）
            String destination = cmd
                    .replace("去", "")
                    .replace("导航到", "")
                    .replace("到", "")
                    .replace("go to", "")
                    .replace("navigate to", "")
                    .trim();

            if (!destination.isEmpty()) {
                destinationName = destination;
                hasDestination = true;
                speak("正在导航到" + destination, speechSpeed);
                startNavigation(destination);
                return;
            }
        }

        // 1.5 手动设置位置（我在XX）- WiFi定位失败时的备用方案
        if (cmd.contains("我在") || cmd.contains("我现在在") || cmd.contains("起点是") ||
                cmd.contains("i am at") || cmd.contains("i'm at")) {

            String location = cmd
                    .replace("我在", "")
                    .replace("我现在在", "")
                    .replace("起点是", "")
                    .replace("i am at", "")
                    .replace("i'm at", "")
                    .trim();

            if (!location.isEmpty()) {
                Position pos = findPositionByName(location);
                if (pos != null) {
                    currentPosition = pos;
                    isLocated = true;
                    navigationService.setCurrentPosition(pos);
                    speak("已手动设置位置为" + pos.getLabel() + "。您可以说去哪里开始导航", speechSpeed);
                    updateDisplay("当前位置：" + pos.getLabel());
                } else {
                    speak("未找到位置：" + location + "。请说正确的位置名称", speechSpeed);
                }
                return;
            }
        }

        // 2. 位置查询
        else if (cmd.contains("我在哪") || cmd.contains("位置") || cmd.contains("where am i") || cmd.contains("location")) {
            if (currentPosition != null) {
                speak("您当前在" + currentPosition.getLabel(), speechSpeed);
            } else {
                speak("当前位置未知，请先定位", speechSpeed);
            }
        }

        // 3. 附近查询
        else if (cmd.contains("附近") || cmd.contains("周围") || cmd.contains("nearby") || cmd.contains("what's around")) {
            if (currentPosition != null) {
                announceNearbyPOIs(currentPosition);
            } else {
                speak("请先定位", speechSpeed);
            }
        }

        // 4. 停止导航
        else if (cmd.contains("停止导航") || cmd.contains("取消导航") || cmd.contains("stop") || cmd.contains("cancel")) {
            if (navigationService.isNavigating()) {
                navigationService.stopNavigation();
                speak("导航已停止", speechSpeed);
            } else {
                speak("当前没有进行中的导航", speechSpeed);
            }
        }

        // 5. 语言切换
        else if (cmd.contains("切换英文") || cmd.contains("switch to english") || cmd.contains("english")) {
            switchToLanguage(VoskSpeechRecognizerService.Language.ENGLISH);
        }
        else if (cmd.contains("切换中文") || cmd.contains("switch to chinese") || cmd.contains("chinese")) {
            switchToLanguage(VoskSpeechRecognizerService.Language.CHINESE);
        }

        // 6. 帮助
        else if (cmd.contains("帮助") || cmd.contains("help")) {
            speak("可以说：去某个地方、我在哪、附近有什么、停止导航、切换语言", speechSpeed);
        }

        // 7. 重复
        else if (cmd.contains("重复") || cmd.contains("再说一遍") || cmd.contains("repeat")) {
            if (!lastSpokenText.isEmpty()) {
                speak(lastSpokenText, speechSpeed);
            }
        }

        // 8. 未识别
        else {
            speak("未识别的指令: " + command, speechSpeed);
        }
    }

    private void initServices() {
        Log.d(TAG, "初始化服务 - 使用工厂模式");

        // 1. 初始化服务工厂（核心）
        serviceFactory = ServiceFactory.getInstance(this);

        // 2. 获取TTS服务（语音播报）
        ttsService = serviceFactory.getTtsService();

        // 3. 获取Vosk服务（语音识别）
        voskService = serviceFactory.getVoskService();

        // 4. 设置Vosk识别监听器（直接处理语音指令）
        voskService.setRecognitionListener(new VoskSpeechRecognizerService.OnRecognitionListener() {
            @Override
            public void onResult(ArrayList<String> results) {
                if (results != null && !results.isEmpty()) {
                    String command = results.get(0);
                    Log.d(TAG, "Vosk识别结果: " + command);

                    runOnUiThread(() -> {
                        updateDisplay("你说: " + command);
                        processVoiceCommand(command); // 直接处理命令
                    });
                }
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "Vosk识别错误: " + errorMsg);
                runOnUiThread(() -> {
                    speak("识别失败，请重试", speechSpeed);
                });
            }
        });

        // 5. 初始化WiFi和定位服务（和原来一样）
        wifiScanner = new L_WiFiScannerServiceImpl();
        wifiScanner.init(this);
        locationService = new L_KnnLocationService(wifiScanner);
        locationService.init(this);

        // 6. 初始化导航服务 - 传入ttsService而不是voiceService
        navigationService = new CompassEnhancedNavigationService(ttsService, locationService);
        navigationService.initSensors(this);

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

            @Override
            public void onStepAnnounced(int stepIndex, int totalSteps, String instruction, String absoluteDirection) {
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
        }, 2000);
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
        List<String> pois = PathParser.getAllPOINames();

        // 尝试模糊匹配
        for (String poi : pois) {
            if (poi.contains(name) || name.contains(poi)) {
                Position pos = new Position();
                pos.setLabel(poi);
                // 这里需要从数据库获取完整的Position信息
                // 为了简化，先返回基础信息
                return pos;
            }
        }

        // 精确匹配
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
            if (isInSettingsMode) {
                speak("请先退出设置模式", speechSpeed);
                return;
            }

            // 使用Vosk服务，而不是voiceAssistant
            if (voskService != null && voskService.isInitialized()) {
                speak("请说出您的指令", speechSpeed);
                vibrate(100);

                // 延迟开始监听，避免TTS干扰
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    voskService.startListening();
                    updateDisplay("正在聆听...");
                }, 800);
            } else {
                String input = etVoiceSimulate.getText().toString().trim();
                if (!input.isEmpty()) {
                    // 手动输入模式
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
                if (input.startsWith("我在") || input.toLowerCase().startsWith("i am at")) {
                    // 直接处理位置输入
                    if (input.startsWith("我在")) {
                        String location = input.substring(2).trim();
                        Position pos = findPositionByName(location);
                        if (pos != null) {
                            currentPosition = pos;
                            isLocated = true;
                            navigationService.setCurrentPosition(pos);
                            speak("已设置当前位置为" + location, speechSpeed);
                            updateDisplay("当前位置：" + location);
                        } else {
                            speak("未找到位置" + location, speechSpeed);
                        }
                    }
                } else {
                    destinationName = input;
                    hasDestination = true;
                    updateDisplay("目的地：" + destinationName);
                    if (isLocated) speak("目的地已设置为" + destinationName + "，点击开始导航", speechSpeed);
                    else {
                        speak("目的地已设置为" + destinationName + "，正在定位", speechSpeed);
                        startLocation();
                    }
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
        if (isInSettingsMode) {
            speak("请先退出设置模式", speechSpeed);
            return;
        }
        vibrate(50);

        // 简化逻辑：单击只进行定位，不开始导航
        if (navigationService.isNavigating()) {
            // 如果在导航中，只更新位置，不停止导航
            speak("正在更新位置", speechSpeed);
            updateDisplay("定位更新中...");
            updateCurrentLocation();
        } else {
            // 不在导航中，只进行定位
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
        try {
            currentLanguage = language;

            // 使用工厂切换语言（同时切换TTS和Vosk）
            if (serviceFactory != null) {
                serviceFactory.switchLanguage(language);
            }

            // 更新TTS语速和语言
            if (ttsService != null && ttsService.isReady()) {
                ttsService.setLanguage(language.locale);
                ttsService.setSpeed(speechSpeed);
            }

            updateSettingsDisplay();
            speak("已切换到" + language.displayName, speechSpeed);
        } finally {
            isSwitchingLanguage = false;
        }
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
        updateDisplay(text);

        if (ttsService != null && ttsService.isReady()) {
            ttsService.speak(text, speed);
        } else {
            Log.e(TAG, "TTS服务未就绪，无法播报: " + text);
        }
    }

    private void vibrate(long ms) { if (vibrator != null) vibrator.vibrate(ms); }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 使用工厂关闭所有服务
        if (serviceFactory != null) {
            serviceFactory.shutdown();
        }

        // 关闭导航
        if (navigationService != null) {
            navigationService.stopNavigation();
        }

        // 清理Handler
        statusUpdateHandler.removeCallbacksAndMessages(null);
    }
}