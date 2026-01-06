package com.example.indoornavblind.service.impl;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.indoornavblind.service.C_SpeechRecognizerService;
import com.example.indoornavblind.service.VoiceService;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能语音助手服务
 *
 * 功能特点：
 * 1. 完全免费：使用Android原生语音识别 + 本地意图引擎
 * 2. 离线可用：无网络时使用预设指令匹配
 * 3. 自动切换：根据网络状态自动选择识别方式
 *
 * 工作流程：
 * 有网络：语音 → Android语音识别 → 本地意图引擎 → 执行动作
 * 无网络：预设指令 / 文本输入 → 本地意图引擎 → 执行动作
 */
public class VoiceAssistantService {
    private static final String TAG = "VoiceAssistant";

    // 依赖服务
    private Context context;
    private C_SpeechRecognizerService speechRecognizer;
    private LocalIntentEngine intentEngine;
    private VoiceService voiceService;

    // 回调
    private VoiceAssistantCallback callback;

    // 状态
    private boolean isListening = false;
    private boolean isInitialized = false;
    private String lastResponse = "";

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
    }

    /**
     * 初始化语音助手
     */
    public void init(VoiceService voiceService, VoiceAssistantCallback callback) {
        this.voiceService = voiceService;
        this.callback = callback;

        // 初始化本地意图引擎（始终可用）
        intentEngine = new LocalIntentEngine(context);
        Log.d(TAG, "本地意图引擎初始化完成");

        // 初始化语音识别（需要网络）
        speechRecognizer = new C_SpeechRecognizerService();
        speechRecognizer.init(context);
        setupSpeechListener();

        isInitialized = true;
        Log.d(TAG, "语音助手初始化完成");
    }

    /**
     * 设置语音识别监听器
     */
    private void setupSpeechListener() {
        speechRecognizer.setRecognitionListener(new C_SpeechRecognizerService.OnRecognitionListener() {
            @Override
            public void onResult(ArrayList<String> results) {
                isListening = false;
                if (callback != null) {
                    callback.onListeningStopped();
                }

                if (results != null && !results.isEmpty()) {
                    String recognizedText = results.get(0);
                    Log.d(TAG, "语音识别结果: " + recognizedText);

                    if (callback != null) {
                        callback.onRecognitionResult(recognizedText);
                    }

                    // 使用意图引擎处理
                    processText(recognizedText);
                } else {
                    handleError("未识别到语音");
                }
            }

            @Override
            public void onError(String errorMsg) {
                isListening = false;
                Log.e(TAG, "语音识别错误: " + errorMsg);

                if (callback != null) {
                    callback.onListeningStopped();
                }

                // 如果是网络错误，提示离线模式
                if (errorMsg.contains("网络") || errorMsg.contains("Network")) {
                    handleError("网络不可用，请使用文字输入或说预设指令");
                } else {
                    handleError(errorMsg);
                }
            }
        });
    }

    /**
     * 开始语音识别
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

        // 检查网络状态
        if (!isNetworkAvailable()) {
            speak("网络不可用，请说预设指令或使用文字输入");
            if (callback != null) {
                callback.onError("离线模式");
            }
            return;
        }

        isListening = true;
        if (callback != null) {
            callback.onListeningStarted();
        }

        speechRecognizer.startListening();
        Log.d(TAG, "开始语音识别");
    }

    /**
     * 停止语音识别
     */
    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        isListening = false;
        if (callback != null) {
            callback.onListeningStopped();
        }
    }

    /**
     * 处理文本输入（用于离线模式或文字输入）
     */
    public void processText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Log.d(TAG, "处理输入: " + text);

        // 使用本地意图引擎识别
        LocalIntentEngine.IntentResult result = intentEngine.recognize(text);
        Log.d(TAG, "意图识别结果: " + result.toString());

        // 根据意图执行相应动作
        dispatchIntent(result);
    }

    /**
     * 分发意图到对应回调
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
                        // 有导航意图但没有目的地，询问用户
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
                    // 尝试给出建议
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

        // 检查是否可能是目的地但没完全匹配
        for (String dest : destinations) {
            if (rawText.contains(dest.substring(0, Math.min(2, dest.length())))) {
                return "您是否想去" + dest + "？请说：去" + dest;
            }
        }

        return "抱歉，我没听懂。您可以说：去浴室、我在哪、帮助等指令";
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
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        isInitialized = false;
        callback = null;
    }
}