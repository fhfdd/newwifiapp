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
import com.example.indoornavblind.factory.ServiceFactory;
import com.example.indoornavblind.model.PathEntity;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.SpeechRecognizerService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.service.impl.EnhancedNavigationService;
import com.example.indoornavblind.util.PathParser;
import com.example.indoornavblind.util.PermissionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主Activity - 重新设计版
 *
 * 功能调整：
 * 1. 合并"定位"和"导航"为一个按钮
 * 2. 独立"语音助手"按钮（可以说任何指令）
 * 3. 设置界面全屏手势操作，双击退出
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // 服务
    private VoiceService voiceService;
    private LocationService locationService;
    private EnhancedNavigationService navigationService;
    private SpeechRecognizerService speechService;
    private WiFiScannerService wifiScanner;
    private Vibrator vibrator;

    // UI控件
    private TextView tvTopDisplay;
    private EditText etVoiceSimulate;
    private Button btnLocateNav, btnVoiceAssistant, btnSettings, btnEmergency;
    private View settingsFullscreen;
    private TextView tvSpeedDisplay, tvLanguageDisplay, tvPaceDisplay;

    // 状态
    private Position currentPosition;
    private boolean isInSettingsMode = false;
    private boolean isLocated = false; // 是否已定位
    private float speechSpeed = 1.0f;
    private String currentLanguage = "中文";
    private Locale currentLocale = Locale.CHINESE;
    private int navigationPace = 3000; // 毫秒
    private String lastSpokenText = "";

    // 手势检测
    private GestureDetector gestureDetector;
    private static final float SPEED_STEP = 0.1f;
    private static final float SPEED_MIN = 0.5f;
    private static final float SPEED_MAX = 2.0f;
    private static final int[] PACE_OPTIONS = {2000, 3000, 4000, 5000};
    private static final String[] LANGUAGES = {"中文", "English", "粵語"};
    private static final Locale[] LOCALES = {Locale.CHINESE, Locale.ENGLISH, Locale.forLanguageTag("yue-HK")};
    private int languageIndex = 0, paceIndex = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "=== 初始化盲人导航系统（重新设计版）===");

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);

        initServices();
        initViews();
        initListeners();

        speak("欢迎使用盲人室内导航系统。点击定位导航按钮开始，语音助手按钮可以语音操作", speechSpeed);
    }

    private void initServices() {
        ServiceFactory factory = ServiceFactory.getInstance(this);
        voiceService = factory.createVoiceService();
        locationService = factory.createLocationService();
        navigationService = new EnhancedNavigationService(voiceService);
        speechService = factory.createSpeechRecognizerService();
        wifiScanner = factory.createWiFiScannerService();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        initSpeechListener();
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
        // 1. 顶部显示区：点击重播
        tvTopDisplay.setOnClickListener(v -> {
            if (!lastSpokenText.isEmpty()) {
                speak(lastSpokenText, speechSpeed);
                vibrate(50);
            }
        });

        // 2. 定位/导航按钮（合并功能）
        btnLocateNav.setOnClickListener(v -> {
            if (isInSettingsMode) {
                speak("请先退出设置模式", speechSpeed);
                return;
            }

            if (!isLocated) {
                // 未定位 → 执行定位
                startLocation();
            } else {
                // 已定位 → 执行导航
                String target = etVoiceSimulate.getText().toString().trim();
                if (target.isEmpty()) {
                    speak("请输入目的地", speechSpeed);
                } else {
                    startNavigation(target);
                }
            }
        });

        // 3. 语音助手按钮（可以说任何指令）
        btnVoiceAssistant.setOnClickListener(v -> {
            if (isInSettingsMode) {
                speak("请先退出设置模式", speechSpeed);
                return;
            }
            speak("请说出您的指令", speechSpeed);
            vibrate(100);
            speechService.startListening();
        });

        // 4. 设置按钮
        btnSettings.setOnClickListener(v -> enterSettingsMode());

        // 5. 紧急求助
        btnEmergency.setOnClickListener(v -> {
            speak("紧急求助已发送", speechSpeed);
            vibrate(500);
        });

        // 6. 设置全屏手势监听
        setupSettingsGestures();
    }

    /**
     * 设置全屏手势监听
     */
    private void setupSettingsGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!isInSettingsMode) return false;

                float deltaX = e2.getX() - e1.getX();
                float deltaY = e2.getY() - e1.getY();

                // 判断是横向还是纵向滑动
                if (Math.abs(deltaX) > Math.abs(deltaY)) {
                    // 横向滑动 → 切换语言
                    if (Math.abs(deltaX) > 100) {
                        switchLanguage();
                        return true;
                    }
                } else {
                    // 纵向滑动 → 调节语速
                    if (Math.abs(deltaY) > 100) {
                        if (deltaY < 0) {
                            // 上滑 → 加速
                            adjustSpeed(SPEED_STEP);
                        } else {
                            // 下滑 → 减速
                            adjustSpeed(-SPEED_STEP);
                        }
                        return true;
                    }
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

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 单击可以切换播报间隔
                if (isInSettingsMode) {
                    switchPace();
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

    private void initSpeechListener() {
        speechService.setRecognitionListener(new SpeechRecognizerService.OnRecognitionListener() {
            @Override
            public void onResult(ArrayList<String> results) {
                if (results != null && !results.isEmpty()) {
                    String command = results.get(0);
                    updateDisplay("识别：" + command);
                    handleVoiceCommand(command);
                }
            }

            @Override
            public void onError(String error) {
                speak("识别失败", speechSpeed);
            }
        });
    }

    /**
     * 处理语音命令
     */
    private void handleVoiceCommand(String command) {
        command = command.toLowerCase();
        Log.d(TAG, "处理语音指令: " + command);

        if (command.contains("定位")) {
            startLocation();
        } else if (command.contains("导航") || command.contains("去") || command.contains("到")) {
            String target = extractTarget(command);
            if (!target.isEmpty()) {
                etVoiceSimulate.setText(target);
                if (isLocated) {
                    startNavigation(target);
                } else {
                    speak("请先定位当前位置", speechSpeed);
                }
            }
        } else if (command.contains("设置")) {
            enterSettingsMode();
        } else if (command.contains("停止")) {
            if (navigationService != null && navigationService.isNavigating()) {
                navigationService.stopNavigation();
            }
        } else if (command.contains("重播") || command.contains("再说一遍")) {
            if (!lastSpokenText.isEmpty()) {
                speak(lastSpokenText, speechSpeed);
            }
        } else {
            speak("可以说：定位、导航到某地、设置、停止", speechSpeed);
        }
    }

    /**
     * 从命令中提取目标地点
     */
    private String extractTarget(String command) {
        command = command.replaceAll("(导航|到|去|找|带我)", "").trim();
        List<String> pois = PathParser.getAllPOINames();
        for (String poi : pois) {
            if (command.contains(poi)) {
                return poi;
            }
        }
        return command;
    }

    /**
     * 开始定位
     */
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
                    btnLocateNav.setText("开始导航");

                    String msg = "定位成功，当前在" + position.getLabel();
                    updateDisplay(msg);
                    speak(msg, speechSpeed);
                    vibrate(200);

                    // 播报附近POI
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        announceNearbyPOIs(position);
                    }, 2000);
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    isLocated = false;
                    updateDisplay("定位失败");
                    speak("定位失败：" + error, speechSpeed);
                    vibrate(300);
                });
            }
        });
    }

    /**
     * 播报附近POI
     */
    private void announceNearbyPOIs(Position position) {
        List<PathEntity> allPaths = PathParser.getAllPaths();
        List<String> nearby = new ArrayList<>();

        for (PathEntity path : allPaths) {
            if (path.getStartLabel_cn().equals(position.getLabel())) {
                nearby.add(path.getEndLabel_cn() + "（" + path.getDistance_cn() + "）");
            }
        }

        if (!nearby.isEmpty()) {
            String nearbyMsg = "附近有：" + String.join("、", nearby.subList(0, Math.min(3, nearby.size())));
            speak(nearbyMsg, speechSpeed);
        }
    }

    /**
     * 开始导航
     */
    private void startNavigation(String target) {
        if (currentPosition == null) {
            speak("请先定位", speechSpeed);
            return;
        }

        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        navigationService.setNavigationConfig(navigationPace, speechSpeed, currentLocale);

        List<PathEntity> path = navigationService.calculatePath();

        if (path.isEmpty()) {
            speak("未找到路径", speechSpeed);
            vibrate(300);
        } else {
            updateDisplay("导航到" + target);
            navigationService.startContinuousNavigation();
            vibrate(100);
        }
    }

    /**
     * 进入设置模式
     */
    private void enterSettingsMode() {
        isInSettingsMode = true;
        settingsFullscreen.setVisibility(View.VISIBLE);
        speak("进入设置。上下滑动调节语速，左右滑动切换语言，单击切换播报间隔，双击退出", speechSpeed);
        updateSettingsDisplay();
        vibrate(100);
    }

    /**
     * 退出设置模式
     */
    private void exitSettingsMode() {
        isInSettingsMode = false;
        settingsFullscreen.setVisibility(View.GONE);
        speak(String.format("设置完成。语速%.1f倍，语言%s，播报间隔%d秒",
                speechSpeed, currentLanguage, navigationPace / 1000), speechSpeed);
        vibrate(200);
    }

    /**
     * 更新设置显示
     */
    private void updateSettingsDisplay() {
        tvSpeedDisplay.setText(String.format("语速：%.1f倍", speechSpeed));
        tvLanguageDisplay.setText("语言：" + currentLanguage);
        tvPaceDisplay.setText(String.format("播报间隔：%d秒", navigationPace / 1000));
    }

    /**
     * 调节语速
     */
    private void adjustSpeed(float delta) {
        speechSpeed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speechSpeed + delta));
        voiceService.setSpeed(speechSpeed);
        updateSettingsDisplay();
        speak(String.format("语速%.1f倍", speechSpeed), speechSpeed);
        vibrate(50);
    }

    /**
     * 切换语言
     */
    private void switchLanguage() {
        languageIndex = (languageIndex + 1) % LANGUAGES.length;
        currentLanguage = LANGUAGES[languageIndex];
        currentLocale = LOCALES[languageIndex];
        voiceService.setLanguage(currentLocale);
        updateSettingsDisplay();
        speak("语言已切换为" + currentLanguage, speechSpeed);
        vibrate(100);
    }

    /**
     * 切换播报间隔
     */
    private void switchPace() {
        paceIndex = (paceIndex + 1) % PACE_OPTIONS.length;
        navigationPace = PACE_OPTIONS[paceIndex];
        updateSettingsDisplay();
        speak(String.format("播报间隔%d秒", navigationPace / 1000), speechSpeed);
        vibrate(100);
    }

    private void updateDisplay(String text) {
        tvTopDisplay.setText(text);
    }

    private void speak(String text, float speed) {
        lastSpokenText = text;
        voiceService.speak(text, speed);
    }

    private void vibrate(long milliseconds) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(milliseconds);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceService != null) voiceService.shutdown();
        if (speechService != null) speechService.destroy();
        if (navigationService != null) navigationService.stopNavigation();
    }
}
