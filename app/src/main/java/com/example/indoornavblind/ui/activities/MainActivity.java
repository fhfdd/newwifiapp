package com.example.indoornavblind.ui.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.indoornavblind.R;
import com.example.indoornavblind.factory.ServiceFactory;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.model.WiFiData;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.NavigationService;
import com.example.indoornavblind.service.SpeechRecognizerService;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.service.WiFiScannerService;
import com.example.indoornavblind.util.PermissionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity"; // 日志标签

    // 服务实例
    private VoiceService voiceService;
    private LocationService locationService;
    private NavigationService navigationService;
    private SpeechRecognizerService speechService;
    private WiFiScannerService wifiScanner;

    // 界面控件
    private TextView tvTopDisplay;
    private EditText etVoiceSimulate;
    private Button btnLocateNav;
    private Button btnVoiceAssistant;
    private Button btnSettings;
    private Button btnEmergency;
    private View settingsBarrier;
    private TextView tvSpeedHint;
    private TextView tvLanguageHint;

    // 状态变量
    private Position currentPosition; // 当前位置缓存
    private boolean isInSettingsMode = false; // 是否在设置模式
    private float speechSpeed = 1.0f; // 默认语速
    private String currentLanguage = "中文"; // 默认语言
    private Locale currentLocale = Locale.CHINESE; // 语言Locale
    private String lastSpokenText = ""; // 上次语音内容（用于重播）

    // 手势相关（设置模式中调整参数）
    private float touchStartY;
    private static final float SPEED_STEP = 0.2f; // 语速调整步长
    private static final int LANGUAGE_COUNT = 3; // 语言数量（中/英/粤）
    private int languageIndex = 0; // 当前语言索引


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: 初始化MainActivity");

        // 1. 请求所有必要权限（定位+WiFi+麦克风）
        Log.d(TAG, "请求所有必要权限");
        PermissionUtil.requestAllPermissions(this);

        // 2. 初始化服务
        initServices();

        // 3. 绑定控件
        initViews();

        // 4. 初始化事件监听
        initListeners();

        // 5. 欢迎语
        speakWelcome();
    }

    // 初始化所有服务（通过工厂获取，解耦依赖）
    private void initServices() {
        Log.d(TAG, "初始化所有服务");
        ServiceFactory factory = ServiceFactory.getInstance(this);
        voiceService = factory.createVoiceService();
        locationService = factory.createLocationService();
        navigationService = factory.createNavigationService();
        speechService = factory.createSpeechRecognizerService();
        wifiScanner = factory.createWiFiScannerService();

        // 初始化语音识别监听器
        initSpeechListener();
    }

    // 绑定布局控件
    private void initViews() {
        Log.d(TAG, "绑定界面控件");
        tvTopDisplay = findViewById(R.id.tv_top_display);
        etVoiceSimulate = findViewById(R.id.et_voice_simulate);
        btnLocateNav = findViewById(R.id.btn_locate_nav);
        btnVoiceAssistant = findViewById(R.id.btn_voice_assistant);
        btnSettings = findViewById(R.id.btn_settings);
        btnEmergency = findViewById(R.id.btn_emergency);
        settingsBarrier = findViewById(R.id.settings_barrier);
        tvSpeedHint = findViewById(R.id.tv_speed_hint);
        tvLanguageHint = findViewById(R.id.tv_language_hint);
    }

    // 初始化所有交互事件
    private void initListeners() {
        Log.d(TAG, "初始化事件监听器");

        // 1. 顶部显示区点击重播
        tvTopDisplay.setOnClickListener(v -> {
            if (!lastSpokenText.isEmpty()) {
                Log.d(TAG, "重播上次语音：" + lastSpokenText);
                voiceService.speak(lastSpokenText, speechSpeed);
            }
        });

        // 2. 语音输入框回车执行
        etVoiceSimulate.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                String command = etVoiceSimulate.getText().toString().trim();
                if (!command.isEmpty()) {
                    Log.d(TAG, "处理输入指令：" + command);
                    handleVoiceCommand(command); // 处理输入指令
                    etVoiceSimulate.setText(""); // 清空输入
                }
                return true;
            }
            return false;
        });

        // 3. 定位/导航按钮
        btnLocateNav.setOnClickListener(v -> {
            Log.d(TAG, "定位/导航按钮点击，当前模式：" + (isInSettingsMode ? "设置模式" : "正常模式"));
            if (isInSettingsMode) {
                voiceService.speak("请先退出设置模式", speechSpeed);
                return;
            }
            if (currentPosition == null) {
                // 未定位：执行定位
                startLocation();
            } else {
                // 已定位：执行导航
                String target = etVoiceSimulate.getText().toString().trim();
                if (target.isEmpty()) {
                    speak("请输入或说出目的地", speechSpeed);
                } else {
                    startNavigation(target);
                }
            }
        });

        // 4. 语音助手按钮
        btnVoiceAssistant.setOnClickListener(v -> {
            Log.d(TAG, "语音助手按钮点击");
            if (isInSettingsMode) {
                voiceService.speak("请先退出设置模式", speechSpeed);
                return;
            }
            if (PermissionUtil.hasAllPermissions(this)) {
                speak("请说出指令，例如导航到厕所", speechSpeed);
                speechService.startListening(); // 启动语音识别
            } else {
                speak("请授予麦克风权限", speechSpeed);
                PermissionUtil.requestAllPermissions(this);
            }
        });

        // 5. 设置按钮
        btnSettings.setOnClickListener(v -> {
            Log.d(TAG, "设置按钮点击，切换设置模式");
            toggleSettingsMode();
        });

        // 6. 紧急求助按钮
        btnEmergency.setOnClickListener(v -> {
            Log.d(TAG, "紧急求助按钮点击");
            if (isInSettingsMode) {
                voiceService.speak("请先退出设置模式", speechSpeed);
                return;
            }
            speak("紧急求助已发送，正在联系管理员", speechSpeed);
            updateDisplay("紧急求助已发送");
        });

        // 7. 设置屏障点击
        settingsBarrier.setOnClickListener(v -> {
            Log.d(TAG, "设置屏障点击，退出设置模式");
            toggleSettingsMode();
        });

        // 8. 设置面板手势监听
        View settingsPanel = findViewById(R.id.settings_panel);
        settingsPanel.setOnTouchListener((v, event) -> handleSettingsTouch(event));
    }

    // 语音识别结果处理
    private void initSpeechListener() {
        Log.d(TAG, "初始化语音识别监听器");
        speechService.setRecognitionListener(new SpeechRecognizerService.OnRecognitionListener() {
            @Override
            public void onResult(ArrayList<String> results) {
                if (results != null && !results.isEmpty()) {
                    String command = results.get(0);
                    Log.d(TAG, "语音识别成功：" + command);
                    updateDisplay("识别到：" + command);
                    handleVoiceCommand(command);
                } else {
                    Log.w(TAG, "语音识别无结果");
                    speak("未识别到指令，请重试", speechSpeed);
                }
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "语音识别错误：" + errorMsg);
                updateDisplay("语音识别错误：" + errorMsg);
                speak("识别失败，请重试", speechSpeed);
            }
        });
    }

    // 处理语音指令
    private void handleVoiceCommand(String command) {
        command = command.toLowerCase();
        Log.d(TAG, "处理语音指令：" + command);
        if (command.contains("定位")) {
            startLocation();
        } else if (command.contains("导航到")) {
            String target = command.replace("导航到", "").trim();
            startNavigation(target);
        } else if (command.contains("设置")) {
            toggleSettingsMode();
        } else if (command.contains("紧急") || command.contains("求助")) {
            btnEmergency.performClick();
        } else if (command.contains("语音助手")) {
            btnVoiceAssistant.performClick();
        } else {
            Log.w(TAG, "未识别的指令：" + command);
            speak("未理解指令，请说定位、导航到某地或设置", speechSpeed);
        }
    }

    // 开始定位（核心排查逻辑）
    private void startLocation() {
        Log.d(TAG, "===== 开始定位流程 =====");
        speak("正在定位，请稍候", speechSpeed);

        // 前置检查：权限
        if (!PermissionUtil.hasAllPermissions(this)) {
            String error = "定位失败：权限不足（需要位置和WiFi权限）";
            Log.e(TAG, error);
            updateDisplay(error);
            speak(error, speechSpeed);
            return;
        }

        // 前置检查：WiFi扫描状态
        List<WiFiData> wifiList = wifiScanner.scanWiFi();
        Log.d(TAG, "当前扫描到的WiFi数量：" + wifiList.size());
        if (wifiList.isEmpty()) {
            String error = "定位失败：未扫描到WiFi信号，请检查WiFi是否开启";
            Log.e(TAG, error);
            updateDisplay(error);
            speak(error, speechSpeed);
            return;
        } else {
            // 打印前3个WiFi信号（避免日志过长）
            for (int i = 0; i < Math.min(3, wifiList.size()); i++) {
                WiFiData wifi = wifiList.get(i);
                Log.d(TAG, "WiFi " + i + "：BSSID=" + wifi.getBssid() + ", RSSI=" + wifi.getRssi());
            }
        }

        // 执行定位
        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                Log.d(TAG, "定位成功：" + position.getLabel() + "（坐标：" + position.getPixelX() + "," + position.getPixelY() + "）");
                runOnUiThread(() -> {
                    currentPosition = position;
                    String msg = "定位成功，当前在" + position.getLabel();
                    updateDisplay(msg);
                    speak(msg, speechSpeed);

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        String nearby = "附近有：厕所（5米）、出口（10米）";
                        speak(nearby, speechSpeed);
                    }, 2000);
                });
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "定位回调失败：" + error);
                runOnUiThread(() -> {
                    updateDisplay("定位失败：" + error);
                    speak("定位失败，" + error, speechSpeed);
                });
            }
        });
    }

    // 开始导航
    private void startNavigation(String target) {
        Log.d(TAG, "开始导航，从" + (currentPosition != null ? currentPosition.getLabel() : "未知位置") + "到" + target);
        if (currentPosition == null) {
            speak("请先定位当前位置", speechSpeed);
            return;
        }
        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        List<?> path = navigationService.calculatePath();
        Log.d(TAG, "导航路径计算结果：" + (path.isEmpty() ? "无路径" : path.size() + "步"));
        if (path.isEmpty()) {
            String msg = "未找到从" + currentPosition.getLabel() + "到" + target + "的路径";
            updateDisplay(msg);
            speak(msg, speechSpeed);
            return;
        }
        String firstStep = navigationService.getNextStepInstruction();
        String msg = "开始导航到" + target + "，" + firstStep;
        updateDisplay(msg);
        speak(msg, speechSpeed);
    }

    // 切换设置模式
    private void toggleSettingsMode() {
        isInSettingsMode = !isInSettingsMode;
        int visibility = isInSettingsMode ? View.VISIBLE : View.GONE;
        settingsBarrier.setVisibility(visibility);
        findViewById(R.id.settings_panel).setVisibility(visibility);
        Log.d(TAG, "切换设置模式：" + (isInSettingsMode ? "进入" : "退出"));

        if (isInSettingsMode) {
            speak("已进入设置模式，上滑加快语速，下滑减慢，点击切换语言，双击退出", speechSpeed);
            updateSettingsHint();
        } else {
            String msg = "已退出设置，当前语速" + speechSpeed + "倍，语言" + currentLanguage;
            speak(msg, speechSpeed);
            updateDisplay(msg);
        }
    }

    // 处理设置面板的触摸事件
    private boolean handleSettingsTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                float touchEndY = event.getY();
                float diffY = touchEndY - touchStartY;

                if (Math.abs(diffY) > 50) {
                    if (diffY < 0) {
                        speechSpeed = Math.min(speechSpeed + SPEED_STEP, 2.0f);
                    } else {
                        speechSpeed = Math.max(speechSpeed - SPEED_STEP, 0.5f);
                    }
                    voiceService.setSpeed(speechSpeed);
                    Log.d(TAG, "语速调整为：" + speechSpeed + "倍");
                    speak("语速已调整为" + speechSpeed + "倍", speechSpeed);
                    updateSettingsHint();
                } else {
                    languageIndex = (languageIndex + 1) % LANGUAGE_COUNT;
                    switch (languageIndex) {
                        case 0:
                            currentLanguage = "中文";
                            currentLocale = Locale.CHINESE;
                            break;
                        case 1:
                            currentLanguage = "英语";
                            currentLocale = Locale.ENGLISH;
                            break;
                        case 2:
                            currentLanguage = "粤语";
                            currentLocale = Locale.forLanguageTag("yue");
                            break;
                    }
                    voiceService.setLanguage(currentLocale);
                    Log.d(TAG, "语言切换为：" + currentLanguage);
                    speak("语言已切换为" + currentLanguage, speechSpeed);
                    updateSettingsHint();
                }
                break;
        }
        return true;
    }

    // 更新设置面板提示文字
    private void updateSettingsHint() {
        tvSpeedHint.setText(String.format("语速：%.1f倍（上滑加快，下滑减慢）", speechSpeed));
        tvLanguageHint.setText("当前语言：" + currentLanguage + "（点击切换）");
    }

    // 更新顶部显示区文字
    private void updateDisplay(String text) {
        tvTopDisplay.setText(text);
    }

    // 语音播报
    private void speak(String text, float speed) {
        lastSpokenText = text;
        Log.d(TAG, "语音播报：" + text + "（语速：" + speed + "）");
        voiceService.speak(text, speed);
    }

    // 欢迎语
    private void speakWelcome() {
        String welcome = "欢迎使用室内导航助手，点击定位导航按钮开始定位，语音助手按钮可语音输入指令";
        speak(welcome, speechSpeed);
        updateDisplay(welcome);
    }

    // 权限请求结果处理
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "权限请求结果回调，requestCode=" + requestCode);
        if (requestCode == PermissionUtil.REQUEST_CODE) {
            if (PermissionUtil.hasAllPermissions(this)) {
                speak("权限已授予，可以使用所有功能", speechSpeed);
                Log.d(TAG, "所有权限已授予");
            } else {
                speak("部分权限未授予，可能影响功能使用", speechSpeed);
                Log.w(TAG, "部分权限未授予");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "销毁MainActivity，释放服务资源");
        if (voiceService != null) voiceService.shutdown();
        if (speechService != null) speechService.destroy();
    }
}