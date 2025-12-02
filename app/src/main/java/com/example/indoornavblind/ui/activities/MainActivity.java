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
import android.content.Intent;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import android.content.ActivityNotFoundException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
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
 * 主Activity - 修复版
 *
 * 修复内容：
 * 1. 添加导航状态实时显示
 * 2. 导航中可以重新定位
 * 3. 改进语音播报和用户反馈
 * 4. 修复各种逻辑问题
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long LONG_PRESS_DURATION = 800; // 长按800毫秒

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
    private Button btnSendLocation, btnWhatsApp;
    private View settingsFullscreen;
    private TextView tvSpeedDisplay, tvLanguageDisplay, tvPaceDisplay;
    private String pendingCallNumber = null;
    private static final int REQUEST_CALL_PERMISSION = 1001;

    // 状态
    private Position currentPosition;
    private boolean isInSettingsMode = false;
    private boolean isLocated = false; // 是否已定位
    private boolean hasDestination = false;
    private String destinationName = "";
    private float speechSpeed = 1.0f;
    private String currentLanguage = "中文";
    private Locale currentLocale = Locale.CHINESE;
    private int navigationPace = 3000; // 毫秒
    private String lastSpokenText = "";

    // 长按处理
    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean isLongPressTriggered = false;

    // 状态更新处理（新增）
    private Handler statusUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdateRunnable;

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

        Log.d(TAG, "=== 初始化盲人导航系统（修复版）===");

        PathParser.init(this);
        PermissionUtil.requestAllPermissions(this);

        initServices();
        initViews();
        initListeners();

        speak("欢迎使用盲人室内导航系统。单击按钮开始定位，长按退出导航", speechSpeed);
    }

    private void initServices() {
        ServiceFactory factory = ServiceFactory.getInstance(this);
        voiceService = factory.createVoiceService();
        locationService = factory.createLocationService();
        navigationService = new EnhancedNavigationService(voiceService, locationService);
        speechService = factory.createSpeechRecognizerService();
        wifiScanner = factory.createWiFiScannerService();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // 设置位置更新回调（新增）
        navigationService.setPositionUpdateCallback(new EnhancedNavigationService.PositionUpdateCallback() {
            @Override
            public void onPositionUpdated(Position newPosition) {
                runOnUiThread(() -> {
                    currentPosition = newPosition;
                    Log.d(TAG, "导航中位置更新：" + newPosition.getLabel());
                });
            }
        });
        
        initSpeechListener();
    }

    private void initViews() {
        tvTopDisplay = findViewById(R.id.tv_top_display);
        etVoiceSimulate = findViewById(R.id.et_voice_simulate);
        btnLocateNav = findViewById(R.id.btn_locate_nav);
        btnVoiceAssistant = findViewById(R.id.btn_voice_assistant);
        btnSettings = findViewById(R.id.btn_settings);
        btnEmergency = findViewById(R.id.btn_emergency);
        btnSendLocation = findViewById(R.id.btn_send_location);
        btnWhatsApp = findViewById(R.id.btn_whatsapp);
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

        // 5. 紧急求助（改为直接拨打电话，需权限）
        btnEmergency.setOnClickListener(v -> {
            String emergencyNumber = "+85212345678"; // 国际格式：+85212345678
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED) {
                makePhoneCall(emergencyNumber);
            } else {
                // 保存号码，授权后继续
                pendingCallNumber = emergencyNumber;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CALL_PHONE},
                        REQUEST_CALL_PERMISSION);
                speak("需要拨打电话权限，正在请求授权", speechSpeed);
            }
            vibrate(500);
        });

        // 6. 设置全屏手势监听
        setupSettingsGestures();

        // 7. 启动状态更新定时器（新增）
        startStatusUpdater();

        // 8. 输入框监听：输入后直接开始导航
        etVoiceSimulate.setOnEditorActionListener((v, actionId, event) -> {
            String input = etVoiceSimulate.getText().toString().trim();
            if (!input.isEmpty()) {
                destinationName = input;
                hasDestination = true;
                updateDisplay("目的地已设置为 " + destinationName);

                if (isLocated) {
                    speak("目的地已设置为 " + destinationName + "，导航开始", speechSpeed);
                    startNavigation(destinationName);   // ✅ 输入后直接触发导航
                } else {
                    speak("目的地已设置为 " + destinationName + "，正在定位当前位置", speechSpeed);
                    startLocation(); // 定位成功后会再触发导航
                }
            }
            return true;
        });

        // 9. WhatsApp 联系与发送位置按钮
        btnWhatsApp.setOnClickListener(v -> {
            // keep existing behavior: open chat with default number
            String phone = "+1234567890"; // TODO: replace
            String message = "Hello from my app";
            openWhatsAppChat(phone, message);
        });

        String message = sb.toString();

        // Try to send via WhatsApp app (text share)
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_TEXT, message);
        sendIntent.setPackage("com.whatsapp");
        try {
            startActivity(sendIntent);
        } catch (ActivityNotFoundException ex1) {
            // Try WhatsApp Business
            try {
                sendIntent.setPackage("com.whatsapp.w4b");
                startActivity(sendIntent);
            } catch (ActivityNotFoundException ex2) {
                // Fallback: show system share sheet
                Intent chooser = Intent.createChooser(sendIntent, "分享位置");
                startActivity(chooser);
            }
        }

    }

    /**
     * 设置定位/导航按钮的单击和长按事件
     */
    private void setupLocateNavButton() {
        btnLocateNav.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // 开始长按计时
                    isLongPressTriggered = false;
                    longPressRunnable = () -> {
                        isLongPressTriggered = true;
                        onLongPressDetected();
                    };
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 取消长按计时
                    longPressHandler.removeCallbacks(longPressRunnable);

                    if (!isLongPressTriggered) {
                        // 单击事件
                        onSingleClickDetected();
                    }
                    return true;
            }
            return false;
        });
    }

    /**
     * 单击按钮（修复版）
     */
    private void onSingleClickDetected() {
        if (isInSettingsMode) {
            speak("请先退出设置模式", speechSpeed);
            return;
        }

        vibrate(50);

        // 判断当前状态
        if (navigationService.isNavigating()) {
            // 正在导航 → 重新定位并播报当前状态
            speak("正在更新位置", speechSpeed);
            updateDisplay("定位更新中...");

            locationService.locate(new LocationService.LocationCallback() {
                @Override
                public void onSuccess(Position position) {
                    runOnUiThread(() -> {
                        currentPosition = position;
                        navigationService.setCurrentPosition(position);
                        String nextStep = navigationService.getNextStepInstruction();
                        String msg = "当前在" + position.getLabel() + "。" + nextStep;
                        speak(msg, speechSpeed);
                        updateDisplay("导航中：" + nextStep);
                    });
                }

                @Override
                public void onFailure(String error) {
                    runOnUiThread(() -> {
                        speak("定位失败，" + error, speechSpeed);
                        // 继续播报下一步
                        String nextStep = navigationService.getNextStepInstruction();
                        speak(nextStep, speechSpeed);
                        updateDisplay("导航中：" + nextStep);
                    });
                }
            });
        } else if (!hasDestination || currentPosition == null) {
            // 没有目的地或没有定位 → 执行定位
            if (!hasDestination && currentPosition != null) {
                announceCurrentEnvironment();
            } else {
                startLocation();
            }
        } else {
            // 有目的地且已定位但未导航 → 开始导航
            speak("导航即将开始", speechSpeed);
            startNavigation(destinationName);
        }
    }

    /**
     * 长按按钮
     */
    private void onLongPressDetected() {
        vibrate(200);
        Log.d(TAG, "检测到长按");

        if (navigationService.isNavigating()) {
            // 正在导航 → 退出导航
            navigationService.stopNavigation();
            hasDestination = false;
            destinationName = "";
            etVoiceSimulate.setText("");

            if (currentPosition != null) {
                String msg = "导航已结束，您当前在" + currentPosition.getLabel() + "附近";
                speak(msg, speechSpeed);
                updateDisplay(msg);
            } else {
                speak("导航已结束", speechSpeed);
                updateDisplay("导航已结束");
            }
        } else {
            // 不在导航中 → 播报当前位置
            if (currentPosition != null) {
                announceCurrentEnvironment();
            } else {
                speak("未定位，正在为您定位", speechSpeed);
                startLocation();
            }
        }
    }

    /**
     * 播报当前位置和周围环境
     */
    private void announceCurrentEnvironment() {
        if (currentPosition == null) {
            speak("当前位置未知，请先定位", speechSpeed);
            return;
        }

        String msg = "当前在" + currentPosition.getLabel() + "。";
        speak(msg, speechSpeed);
        updateDisplay("当前位置：" + currentPosition.getLabel());

        // 延迟2秒后播报附近POI
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            announceNearbyPOIs(currentPosition);

            if (!hasDestination) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    speak("请说出目的地，或通过语音助手设置", speechSpeed);
                }, 3000);
            }
        }, 2000);
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
                destinationName = target;
                hasDestination = true;

                if (isLocated) {
                    speak("目的地已设置为" + target + "，点击按钮开始导航", speechSpeed);
                    // 不自动开始，让用户确认
                } else {
                    speak("目的地已设置为" + target + "，正在定位当前位置", speechSpeed);
                    startLocation();
                }
            } else {
                speak("未识别到目的地，请重新说出完整的目的地名称", speechSpeed);
            }
        } else if (command.contains("设置")) {
            enterSettingsMode();
        } else if (command.contains("停止") || command.contains("结束")) {
            if (navigationService != null && navigationService.isNavigating()) {
                navigationService.stopNavigation();
                hasDestination = false;
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
     * 开始定位（改进版）
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
                    navigationService.setCurrentPosition(position);

                    String msg = "定位成功，当前在" + position.getLabel();
                    updateDisplay("当前位置：" + position.getLabel());
                    speak(msg, speechSpeed);
                    vibrate(200);

                    // 播报附近POI
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        announceNearbyPOIs(position);

                        if (hasDestination) {
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                speak("目的地已设置为" + destinationName + "，点击按钮开始导航", speechSpeed);
                                // 不自动开始导航，让用户主动点击
                            }, 2000);
                        } else {
                            // 提示用户设置目的地
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                speak("请说出目的地，或通过语音助手设置", speechSpeed);
                            }, 3000);
                        }
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

        locationService.locate(new LocationService.LocationCallback() {
            @Override
            public void onSuccess(Position position) {
                currentPosition = position;
                navigationService.setCurrentPosition(position);
                updateDisplay("当前位置更新：" + position.getLabel());
                speak("当前位置更新：" + position.getLabel(), speechSpeed);
            }

            @Override
            public void onFailure(String error) {
                speak("导航启动时定位失败：" + error, speechSpeed);
            }
        });

        destinationName = target;
        hasDestination = true;

        navigationService.setCurrentPosition(currentPosition);
        navigationService.setTarget(target);
        navigationService.setNavigationConfig(navigationPace, speechSpeed, currentLocale);

        List<PathEntity> path = navigationService.calculatePath();

        if (path.isEmpty()) {
            speak("未找到路径", speechSpeed);
            vibrate(300);
            hasDestination = false;
        } else {
            updateDisplay("导航到" + target);
            navigationService.startContinuousNavigation();
            vibrate(100);
        }
    }

    /**
     * 启动状态更新器（新增）
     */
    private void startStatusUpdater() {
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNavigationStatus();
                statusUpdateHandler.postDelayed(this, 1000); // 每秒更新
            }
        };
        statusUpdateHandler.post(statusUpdateRunnable);
    }

    /**
     * 更新导航状态显示（新增）
     */
    private void updateNavigationStatus() {
        String status;
        if (navigationService.isNavigating()) {
            status = "正在导航到" + destinationName;
            if (currentPosition != null) {
                status += " | 当前：" + currentPosition.getLabel();
            }
        } else if (hasDestination) {
            status = "目的地：" + destinationName + " | 点击开始导航";
        } else if (currentPosition != null) {
            status = "当前位置：" + currentPosition.getLabel();
        } else {
            status = "请点击按钮开始定位";
        }
        updateDisplay(status);
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
        updateDisplay(text);
    }

    private void vibrate(long milliseconds) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(milliseconds);
        }
    }

    private void makePhoneCall(String number) {
        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + number));
            startActivity(callIntent);
            speak("正在拨打电话", speechSpeed);
            vibrate(200);
        } catch (Exception e) {
            speak("无法拨打电话，请手动拨号", speechSpeed);
            vibrate(300);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CALL_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingCallNumber != null) {
                    makePhoneCall(pendingCallNumber);
                    pendingCallNumber = null;
                }
            } else {
                speak("权限被拒绝，无法拨打电话", speechSpeed);
            }
        }
    }

    /**
     * Open a WhatsApp chat to a phone number with a prefilled message.
     * Falls back to WhatsApp Business or the browser if WhatsApp isn't installed.
     */
    private void openWhatsAppChat(String phone, String message) {
        if (phone == null || phone.trim().isEmpty()) return;

        String cleaned = phone.replaceAll("[^0-9]", "");
        String encodedMessage;
        try {
            encodedMessage = URLEncoder.encode(message == null ? "" : message, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encodedMessage = message == null ? "" : message;
        }

        String url = "https://wa.me/" + cleaned + (encodedMessage.isEmpty() ? "" : "?text=" + encodedMessage);

        Intent appIntent = new Intent(Intent.ACTION_VIEW);
        appIntent.setData(Uri.parse(url));
        appIntent.setPackage("com.whatsapp");

        try {
            startActivity(appIntent);
        } catch (ActivityNotFoundException e1) {
            try {
                appIntent.setPackage("com.whatsapp.w4b");
                startActivity(appIntent);
            } catch (ActivityNotFoundException e2) {
                // Fallback to browser
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceService != null) voiceService.shutdown();
        if (speechService != null) speechService.destroy();
        if (navigationService != null) navigationService.stopNavigation();
        longPressHandler.removeCallbacksAndMessages(null);
        statusUpdateHandler.removeCallbacksAndMessages(null);  // 新增：停止状态更新
    }
}