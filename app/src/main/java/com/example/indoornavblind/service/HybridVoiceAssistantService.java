package com.example.indoornavblind.service;

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
import com.example.indoornavblind.service.impl.LocalIntentEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合模式语音助手服务
 * 
 * 核心特性：
 * 1. 智能切换：Vosk（离线优先） + Google（在线备选）
 * 2. 降级策略：Vosk 失败自动切换到 Google
 * 3. 用户可控：允许用户选择识别引擎
 * 
 * 适用场景：
 * - 固定指令（如"去浴室"）：优先 Vosk（准确率高）
 * - 自由对话：降级到 Google
 * - 无网环境：强制 Vosk
 */
public class HybridVoiceAssistantService {
    private static final String TAG = "HybridVoiceAssistant";
    private static final String PREFS_NAME = "voice_settings";
    private static final String KEY_PREFER_OFFLINE = "prefer_offline";
    
    // 识别引擎
    public enum RecognizerEngine {
        VOSK_OFFLINE,    // Vosk 离线识别
        GOOGLE_ONLINE,   // Google 在线识别
        AUTO             // 自动选择
    }
    
    // 依赖服务
    private Context context;
    private VoskSpeechRecognizerService voskRecognizer;
    private C_SpeechRecognizerService googleRecognizer;
    private LocalIntentEngine intentEngine;
    private VoiceService voiceService;
    
    // 配置
    private RecognizerEngine preferredEngine = RecognizerEngine.AUTO;
    private RecognizerEngine currentEngine = null;
    private boolean allowFallback = true; // 是否允许降级
    
    // 回调
    private VoiceAssistantCallback callback;
    
    // 状态
    private boolean isListening = false;
    private boolean voskInitialized = false;
    private boolean googleInitialized = false;
    private int voskRetryCount = 0;
    private static final int MAX_VOSK_RETRY = 2;
    
    // Handler
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * 语音助手回调接口（与原接口兼容）
     */
    public interface VoiceAssistantCallback {
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
        
        void onListeningStarted();
        void onListeningStopped();
        void onRecognitionResult(String text);
        void onError(String errorMsg);
    }
    
    public HybridVoiceAssistantService(Context context) {
        this.context = context;
        loadPreferences();
    }
    
    /**
     * 初始化混合语音助手
     */
    public void init(VoiceService voiceService, VoiceAssistantCallback callback) {
        this.voiceService = voiceService;
        this.callback = callback;
        
        // 初始化本地意图引擎
        intentEngine = new LocalIntentEngine(context);
        Log.d(TAG, "本地意图引擎初始化完成");
        
        // 异步初始化两个识别器
        initializeRecognizers();
    }
    
    /**
     * 异步初始化识别器
     */
    private void initializeRecognizers() {
        // 初始化 Vosk（优先级高）
        new Thread(() -> {
            try {
                voskRecognizer = new VoskSpeechRecognizerService();
                voskRecognizer.init(context, null); // 使用默认模型
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

                
                voskInitialized = true;
                Log.d(TAG, "Vosk 离线识别初始化完成");
                
                // 通知 UI
                notifyEngineStatus("Vosk 离线模式已就绪");
                
            } catch (Exception e) {
                Log.e(TAG, "Vosk 初始化失败", e);
                voskInitialized = false;
            }
        }).start();
        
        // 初始化 Google（作为备选）
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
        Log.d(TAG, "Google 在线识别初始化完成");
    }
    
    /**
     * 开始语音识别（智能选择引擎）
     */
    public void startListening() {
        if (isListening) {
            Log.d(TAG, "已在监听中");
            return;
        }
        
        // 根据配置选择识别引擎
        RecognizerEngine engine = selectRecognizerEngine();
        
        if (engine == RecognizerEngine.VOSK_OFFLINE) {
            startVoskRecognition();
        } else if (engine == RecognizerEngine.GOOGLE_ONLINE) {
            startGoogleRecognition();
        } else {
            handleError("没有可用的识别引擎");
        }
    }
    
    /**
     * 智能选择识别引擎
     */
    private RecognizerEngine selectRecognizerEngine() {
        // 1. 用户明确指定了引擎
        if (preferredEngine != RecognizerEngine.AUTO) {
            if (preferredEngine == RecognizerEngine.VOSK_OFFLINE && voskInitialized) {
                return RecognizerEngine.VOSK_OFFLINE;
            } else if (preferredEngine == RecognizerEngine.GOOGLE_ONLINE && googleInitialized) {
                return RecognizerEngine.GOOGLE_ONLINE;
            }
        }
        
        // 2. 自动模式：优先级决策
        
        // 2.1 Vosk 已就绪且网络不稳定 -> 使用 Vosk
        if (voskInitialized && !isNetworkStable()) {
            Log.d(TAG, "网络不稳定，使用 Vosk 离线识别");
            return RecognizerEngine.VOSK_OFFLINE;
        }
        
        // 2.2 Vosk 已就绪且用户偏好离线 -> 使用 Vosk
        if (voskInitialized && preferOfflineMode()) {
            Log.d(TAG, "用户偏好离线模式，使用 Vosk");
            return RecognizerEngine.VOSK_OFFLINE;
        }
        
        // 2.3 Vosk 已就绪且重试次数未超限 -> 使用 Vosk
        if (voskInitialized && voskRetryCount < MAX_VOSK_RETRY) {
            Log.d(TAG, "使用 Vosk 离线识别");
            return RecognizerEngine.VOSK_OFFLINE;
        }
        
        // 2.4 降级到 Google（如果有网络）
        if (googleInitialized && isNetworkAvailable()) {
            Log.d(TAG, "降级到 Google 在线识别");
            return RecognizerEngine.GOOGLE_ONLINE;
        }
        
        // 2.5 最后尝试：即使 Vosk 失败过，也尝试使用
        if (voskInitialized) {
            Log.d(TAG, "强制使用 Vosk（无其他选项）");
            return RecognizerEngine.VOSK_OFFLINE;
        }
        
        // 2.6 无可用引擎
        return null;
    }
    
    /**
     * 启动 Vosk 识别
     */
    private void startVoskRecognition() {
        if (!voskInitialized) {
            handleError("Vosk 未初始化");
            // 尝试降级到 Google
            if (allowFallback) {
                startGoogleRecognition();
            }
            return;
        }
        
        isListening = true;
        currentEngine = RecognizerEngine.VOSK_OFFLINE;
        
        if (callback != null) {
            callback.onListeningStarted();
        }
        
        voskRecognizer.startListening();
        Log.d(TAG, "Vosk 离线识别已启动");
    }
    
    /**
     * 启动 Google 识别
     */
    private void startGoogleRecognition() {
        if (!googleInitialized) {
            handleError("Google 识别未初始化");
            return;
        }
        
        if (!isNetworkAvailable()) {
            handleError("无网络连接，无法使用在线识别");
            // 尝试降级到 Vosk
            if (allowFallback && voskInitialized) {
                startVoskRecognition();
            }
            return;
        }
        
        isListening = true;
        currentEngine = RecognizerEngine.GOOGLE_ONLINE;
        
        if (callback != null) {
            callback.onListeningStarted();
        }
        
        googleRecognizer.startListening();
        Log.d(TAG, "Google 在线识别已启动");
    }
    
    /**
     * 处理 Vosk 识别结果
     */
    private void handleVoskResult(ArrayList<String> results) {
        isListening = false;
        voskRetryCount = 0; // 成功后重置重试计数
        
        if (callback != null) {
            callback.onListeningStopped();
        }
        
        if (results != null && !results.isEmpty()) {
            String recognizedText = results.get(0);
            Log.d(TAG, "Vosk 识别结果: " + recognizedText);
            
            if (callback != null) {
                callback.onRecognitionResult(recognizedText + " (离线)");
            }
            
            // 处理识别结果
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
        Log.e(TAG, "Vosk 识别错误: " + errorMsg);
        
        if (callback != null) {
            callback.onListeningStopped();
        }
        
        voskRetryCount++;
        
        // 尝试降级到 Google
        if (allowFallback && googleInitialized && isNetworkAvailable()) {
            Log.d(TAG, "Vosk 失败，降级到 Google 识别");
            speak("离线识别失败，切换到在线模式");
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
            Log.d(TAG, "Google 识别结果: " + recognizedText);
            
            if (callback != null) {
                callback.onRecognitionResult(recognizedText + " (在线)");
            }
            
            // 处理识别结果
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
        Log.e(TAG, "Google 识别错误: " + errorMsg);
        
        if (callback != null) {
            callback.onListeningStopped();
        }
        
        // 如果是网络错误，尝试切换到 Vosk
        if ((errorMsg.contains("网络") || errorMsg.contains("Network")) && allowFallback && voskInitialized) {
            Log.d(TAG, "Google 网络错误，切换到 Vosk");
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
        if (currentEngine == RecognizerEngine.VOSK_OFFLINE && voskRecognizer != null) {
            voskRecognizer.stopListening();
        } else if (currentEngine == RecognizerEngine.GOOGLE_ONLINE && googleRecognizer != null) {
            googleRecognizer.stopListening();
        }
        
        isListening = false;
        currentEngine = null;
        
        if (callback != null) {
            callback.onListeningStopped();
        }
    }
    
    /**
     * 处理识别文本（与原实现相同）
     */
    public void processText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        
        Log.d(TAG, "处理输入: " + text);
        
        LocalIntentEngine.IntentResult result = intentEngine.recognize(text);
        Log.d(TAG, "意图识别结果: " + result.toString());
        
        dispatchIntent(result);
    }
    
    /**
     * 分发意图（与原实现相同，省略重复代码）
     */
    private void dispatchIntent(LocalIntentEngine.IntentResult result) {
        // ... 与原 VoiceAssistantService 相同的实现
    }
    
    /**
     * 设置首选识别引擎
     */
    public void setPreferredEngine(RecognizerEngine engine) {
        this.preferredEngine = engine;
        savePreferences();
        
        String engineName;
        switch (engine) {
            case VOSK_OFFLINE:
                engineName = "离线模式 (Vosk)";
                break;
            case GOOGLE_ONLINE:
                engineName = "在线模式 (Google)";
                break;
            default:
                engineName = "自动模式";
        }
        
        Log.d(TAG, "切换到: " + engineName);
        speak("已切换到" + engineName);
    }
    
    /**
     * 获取当前使用的引擎
     */
    public RecognizerEngine getCurrentEngine() {
        return currentEngine;
    }
    
    /**
     * 获取引擎状态
     */
    public String getEngineStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Vosk 离线: ").append(voskInitialized ? "✓" : "✗").append("\n");
        status.append("Google 在线: ").append(googleInitialized ? "✓" : "✗").append("\n");
        status.append("当前引擎: ");
        
        if (currentEngine == RecognizerEngine.VOSK_OFFLINE) {
            status.append("Vosk 离线模式");
        } else if (currentEngine == RecognizerEngine.GOOGLE_ONLINE) {
            status.append("Google 在线模式");
        } else {
            status.append("未使用");
        }
        
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
     * 检查网络是否稳定（扩展判断）
     */
    private boolean isNetworkStable() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                // WiFi 认为稳定，移动网络需要检查信号强度
                return activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
            }
        }
        return false;
    }
    
    /**
     * 检查用户是否偏好离线模式
     */
    private boolean preferOfflineMode() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PREFER_OFFLINE, true); // 默认偏好离线
    }
    
    /**
     * 加载用户偏好设置
     */
    private void loadPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean preferOffline = prefs.getBoolean(KEY_PREFER_OFFLINE, true);
        
        if (preferOffline) {
            preferredEngine = RecognizerEngine.VOSK_OFFLINE;
        } else {
            preferredEngine = RecognizerEngine.AUTO;
        }
    }
    
    /**
     * 保存用户偏好设置
     */
    private void savePreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putBoolean(KEY_PREFER_OFFLINE, preferredEngine == RecognizerEngine.VOSK_OFFLINE)
            .apply();
    }
    
    /**
     * 通知引擎状态变化
     */
    private void notifyEngineStatus(String message) {
        mainHandler.post(() -> {
            if (callback != null) {
                // 可以通过 onError 传递状态信息，或者扩展接口
                Log.d(TAG, message);
            }
        });
    }
    
    /**
     * 语音播报
     */
    private void speak(String text) {
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
     * 销毁资源
     */
    public void destroy() {
        if (voskRecognizer != null) {
            voskRecognizer.destroy();
        }
        if (googleRecognizer != null) {
            googleRecognizer.destroy();
        }
        callback = null;
    }
}
