package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.indoornavblind.service.C_SpeechRecognizerService;
import com.example.indoornavblind.service.VoskSpeechRecognizerService;
import com.example.indoornavblind.service.VoiceService;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能语音助手服务（Vosk 增强版）
 *
 * 功能特点：
 * 1. 优先使用 Vosk 离线识别（快速、准确、隐私）
 * 2. 保留 Google 在线识别作为备选
 * 3. 自动降级策略：Vosk 失败自动切换到 Google
 * 4. 完全本地意图处理
 */
public class VoiceAssistantService {
    private static final String TAG = "VoiceAssistant";
    private static final String PREFS_NAME = "voice_settings";
    private static final String KEY_USE_VOSK = "use_vosk";

    // 依赖服务
    private Context context;
    private VoskSpeechRecognizerService voskRecognizer;      // Vosk 离线识别
    private C_SpeechRecognizerService googleRecognizer;       // Google 在线识别
    private LocalIntentEngine intentEngine;
    private VoiceService voiceService;

    // 回调
    private VoiceAssistantCallback callback;

    // 状态
    private boolean isListening = false;
    private boolean isInitialized = false;
    private boolean useVosk = true;  // 默认使用 Vosk
    private boolean voskInitialized = false;
    private boolean googleInitialized = false;
    private String lastResponse = "";
    private int voskRetryCount = 0;
    private static final int MAX_VOSK_RETRY = 2;

    // Handler
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 语音助手回调接口
     */
    public interface VoiceAssistantCallback {
        // 意图回调
        void onNavigateIntent(String destination);
        void onLocateIntent();
        void onQueryLocationIntent();
        void onQueryNearbyIntent();
        void onQueryProgressIntent();
        void onStartNavigationIntent();
        void onStopNavigationIntent();
        void onRepeatIntent();
        void onHelpIntent(String helpText);
        void onSettingsIntent();
        void onSpeedUpIntent();
        void onSpeedDownIntent();
        void onEmergencyIntent();
        void onUnknownIntent(String rawText);

        // 状态回调
        void onListeningStarted();
        void onListeningStopped();
        void onRecognitionResult(String text);
        void onError(String errorMsg);
    }

    public VoiceAssistantService(Context context) {
        this.context = context;
        loadPreferences();
    }

    /**
     * 初始化语音助手
     */
    public void init(VoiceService voiceService, VoiceAssistantCallback callback) {
        this.voiceService = voiceService;
        this.callback = callback;

        // 初始化本地意图引擎
        intentEngine = new LocalIntentEngine(context);
        Log.d(TAG, "本地意图引擎初始化完成");

        // 异步初始化两个识别器
        initializeRecognizers();

        isInitialized = true;
        Log.d(TAG, "语音助手初始化完成");
    }

    /**
     * 异步初始化识别器
     */
    private void initializeRecognizers() {
        // 初始化 Vosk（优先级高，后台加载）
        new Thread(() -> {
            try {
                voskRecognizer = new VoskSpeechRecognizerService();
                voskRecognizer.init(context, null);

                voskRecognizer.setRecognitionListener(new VoskSpeechRecognizerService.OnRecognitionListener() {
                    @Override
                    public void onResult(ArrayList<String> results) {
                        handleVoskResult(results);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        handleVoskError(errorMsg);
                    }
                });

                // 等待模型加载完成
                int waitCount = 0;
                while (!voskRecognizer.isInitialized() && waitCount < 50) {
                    Thread.sleep(100);
                    waitCount++;
                }

                if (voskRecognizer.isInitialized()) {
                    voskInitialized = true;
                    Log.d(TAG, "✓ Vosk 离线识别初始化完成");
                    notifyStatus("Vosk 离线模式已就绪");
                } else {
                    Log.w(TAG, "Vosk 初始化超时");
                }

            } catch (Exception e) {
                Log.e(TAG, "Vosk 初始化失败", e);
                voskInitialized = false;
            }
        }).start();

        // 初始化 Google（作为备选，快速初始化）
        try {
            googleRecognizer = new C_SpeechRecognizerService();
            googleRecognizer.init(context);
            googleRecognizer.setRecognitionListener(new C_SpeechRecognizerService.OnRecognitionListener() {
                @Override
                public void onResult(ArrayList<String> results) {
                    handleGoogleResult(results);
                }

                @Override
                public void onError(String errorMsg) {
                    handleGoogleError(errorMsg);
                }
            });
            googleInitialized = true;
            Log.d(TAG, "✓ Google 在线识别初始化完成");
        } catch (Exception e) {
            Log.e(TAG, "Google 识别初始化失败", e);
            googleInitialized = false;
        }
    }

    /**
     * 开始语音识别（智能选择引擎）
     */
    public void startListening() {
        if (!isInitialized) {
            handleError("语音助手未初始化");
            return;
        }

        if (isListening) {
            Log.d(TAG, "已在监听中");
            return;
        }

        // 智能选择识别引擎
        if (useVosk && voskInitialized) {
            // 优先使用 Vosk
            startVoskRecognition();
        } else if (googleInitialized && isNetworkAvailable()) {
            // 降级到 Google
            startGoogleRecognition();
        } else if (voskInitialized) {
            // 即使 Vosk 失败过，也再试一次（无其他选项）
            startVoskRecognition();
        } else {
            handleError("识别引擎未就绪，请稍后再试");
        }
    }

    /**
     * 启动 Vosk 识别
     */
    private void startVoskRecognition() {
        isListening = true;

        if (callback != null) {
            callback.onListeningStarted();
        }

        voskRecognizer.startListening();
        Log.d(TAG, "→ 使用 Vosk 离线识别");
    }

    /**
     * 启动 Google 识别
     */
    private void startGoogleRecognition() {
        if (!isNetworkAvailable()) {
            handleError("无网络连接，无法使用在线识别");
            return;
        }

        isListening = true;

        if (callback != null) {
            callback.onListeningStarted();
        }

        googleRecognizer.startListening();
        Log.d(TAG, "→ 使用 Google 在线识别");
    }

    /**
     * 处理 Vosk 识别结果
     */
    private void handleVoskResult(ArrayList<String> results) {
        isListening = false;
        voskRetryCount = 0; // 成功后重置

        if (callback != null) {
            callback.onListeningStopped();
        }

        if (results != null && !results.isEmpty()) {
            String recognizedText = results.get(0);
            Log.d(TAG, "✓ Vosk 识别: " + recognizedText);

            if (callback != null) {
                callback.onRecognitionResult(recognizedText + " (离线)");
            }

            processText(recognizedText);
        } else {
            handleError("未识别到语音");
        }
    }

    /**
     * 处理 Vosk 识别错误
     */
    private void handleVoskError(String errorMsg) {
        isListening = false;
        Log.e(TAG, "✗ Vosk 错误: " + errorMsg);

        if (callback != null) {
            callback.onListeningStopped();
        }

        voskRetryCount++;

        // 降级策略：失败后切换到 Google
        if (voskRetryCount < MAX_VOSK_RETRY && googleInitialized && isNetworkAvailable()) {
            Log.d(TAG, "→ 降级到 Google 识别");
            speak("切换到在线模式");
            startGoogleRecognition();
        } else {
            handleError("语音识别失败: " + errorMsg);
        }
    }

    /**
     * 处理 Google 识别结果
     */
    private void handleGoogleResult(ArrayList<String> results) {
        isListening = false;

        if (callback != null) {
            callback.onListeningStopped();
        }

        if (results != null && !results.isEmpty()) {
            String recognizedText = results.get(0);
            Log.d(TAG, "✓ Google 识别: " + recognizedText);

            if (callback != null) {
                callback.onRecognitionResult(recognizedText + " (在线)");
            }

            processText(recognizedText);
        } else {
            handleError("未识别到语音");
        }
    }

    /**
     * 处理 Google 识别错误
     */
    private void handleGoogleError(String errorMsg) {
        isListening = false;
        Log.e(TAG, "✗ Google 错误: " + errorMsg);

        if (callback != null) {
            callback.onListeningStopped();
        }

        // 如果是网络错误且 Vosk 可用，切换到 Vosk
        if ((errorMsg.contains("网络") || errorMsg.contains("Network")) && voskInitialized) {
            Log.d(TAG, "→ 切换到 Vosk 离线模式");
            speak("网络不可用，切换到离线模式");
            startVoskRecognition();
        } else {
            handleError("语音识别失败: " + errorMsg);
        }
    }

    /**
     * 停止语音识别
     */
    public void stopListening() {
        if (voskRecognizer != null && voskRecognizer.isListening()) {
            voskRecognizer.stopListening();
        }
        if (googleRecognizer != null) {
            googleRecognizer.stopListening();
        }

        isListening = false;

        if (callback != null) {
            callback.onListeningStopped();
        }
    }

    /**
     * 处理文本输入（保持原有逻辑）
     */
    public void processText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Log.d(TAG, "处理输入: " + text);

        // 使用本地意图引擎识别
        LocalIntentEngine.IntentResult result = intentEngine.recognize(text);
        Log.d(TAG, "意图识别: " + result.toString());

        // 分发意图
        dispatchIntent(result);
    }

    /**
     * 分发意图到对应回调（保持原有逻辑）
     */
    private void dispatchIntent(LocalIntentEngine.IntentResult result) {
        if (callback == null) {
            return;
        }

        mainHandler.post(() -> {
            switch (result.intent) {
                case NAVIGATE:
                    if (result.destination != null) {
                        callback.onNavigateIntent(result.destination);
                    } else {
                        speak("请问您要去哪里？可以说：去浴室、去门口等");
                        callback.onUnknownIntent(result.rawText);
                    }
                    break;

                case LOCATE:
                    callback.onLocateIntent();
                    break;

                case QUERY_LOCATION:
                    callback.onQueryLocationIntent();
                    break;

                case QUERY_NEARBY:
                    callback.onQueryNearbyIntent();
                    break;

                case QUERY_PROGRESS:
                    callback.onQueryProgressIntent();
                    break;

                case START_NAVIGATION:
                    callback.onStartNavigationIntent();
                    break;

                case STOP_NAVIGATION:
                    callback.onStopNavigationIntent();
                    break;

                case REPEAT:
                    callback.onRepeatIntent();
                    break;

                case HELP:
                    String helpText = intentEngine.getHelpText();
                    callback.onHelpIntent(helpText);
                    break;

                case SETTINGS:
                    callback.onSettingsIntent();
                    break;

                case SPEED_UP:
                    callback.onSpeedUpIntent();
                    break;

                case SPEED_DOWN:
                    callback.onSpeedDownIntent();
                    break;

                case EMERGENCY:
                    callback.onEmergencyIntent();
                    break;

                case UNKNOWN:
                default:
                    String suggestion = getSuggestion(result.rawText);
                    speak(suggestion);
                    callback.onUnknownIntent(result.rawText);
                    break;
            }
        });
    }

    /**
     * 获取未知指令的建议
     */
    private String getSuggestion(String rawText) {
        List<String> destinations = intentEngine.getAvailableDestinations();

        for (String dest : destinations) {
            if (rawText.contains(dest.substring(0, Math.min(2, dest.length())))) {
                return "您是否想去" + dest + "？请说：去" + dest;
            }
        }

        return "抱歉，我没听懂。您可以说：去浴室、我在哪、帮助等指令";
    }

    /**
     * 切换识别模式
     */
    public void toggleRecognitionMode() {
        useVosk = !useVosk;
        savePreferences();

        String mode = useVosk ? "离线模式 (Vosk)" : "在线模式 (Google)";
        Log.d(TAG, "切换到: " + mode);
        speak("已切换到" + mode);
    }

    /**
     * 获取引擎状态
     */
    public String getEngineStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Vosk 离线: ").append(voskInitialized ? "✓" : "✗").append("\n");
        status.append("Google 在线: ").append(googleInitialized ? "✓" : "✗").append("\n");
        status.append("当前模式: ").append(useVosk ? "离线优先" : "在线优先");
        return status.toString();
    }

    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }

    /**
     * 加载用户偏好
     */
    private void loadPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        useVosk = prefs.getBoolean(KEY_USE_VOSK, true); // 默认使用 Vosk
    }

    /**
     * 保存用户偏好
     */
    private void savePreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_USE_VOSK, useVosk).apply();
    }

    /**
     * 通知状态
     */
    private void notifyStatus(String message) {
        mainHandler.post(() -> {
            Log.d(TAG, message);
            // 可以通过回调通知 UI
        });
    }

    /**
     * 语音播报
     */
    private void speak(String text) {
        lastResponse = text;
        if (voiceService != null) {
            voiceService.speak(text, 1.0f);
        }
    }

    /**
     * 处理错误
     */
    private void handleError(String errorMsg) {
        if (callback != null) {
            callback.onError(errorMsg);
        }
        speak(errorMsg);
    }

    /**
     * 获取上次响应
     */
    public String getLastResponse() {
        return lastResponse;
    }

    /**
     * 获取所有可用目的地
     */
    public List<String> getAvailableDestinations() {
        if (intentEngine != null) {
            return intentEngine.getAvailableDestinations();
        }
        return new ArrayList<>();
    }

    /**
     * 是否正在监听
     */
    public boolean isListening() {
        return isListening;
    }

    /**
     * 是否有网络
     */
    public boolean hasNetwork() {
        return isNetworkAvailable();
    }

    /**
     * 销毁资源
     */
    public void destroy() {
        if (voskRecognizer != null) {
            voskRecognizer.destroy();
        }
        if (googleRecognizer != null) {
            googleRecognizer.destroy();
        }
        isInitialized = false;
        callback = null;
    }
}