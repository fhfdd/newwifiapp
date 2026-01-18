package com.example.indoornavblind.service;

import static com.example.indoornavblind.service.impl.LocalIntentEngine.Intent.LOCATE;
import static com.example.indoornavblind.service.impl.LocalIntentEngine.Intent.NAVIGATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.indoornavblind.service.impl.LocalIntentEngine;

import java.util.ArrayList;

/**
 * 完全离线语音助手服务
 */
public class OfflineVoiceAssistantService {
    private static final String TAG = "OfflineVoiceAssistant";
    private static final String PREFS_NAME = "voice_settings";

    // 依赖服务
    private Context context;
    private VoskSpeechRecognizerService voskRecognizer;
    private LocalIntentEngine intentEngine;
    private VoiceService voiceService;

    // 回调
    private VoiceAssistantCallback callback;

    // 状态
    private boolean isListening = false;
    private boolean isInitialized = false;

    // Handler
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 语音助手回调接口
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

        // 位置输入相关（新增）
        void onLocationInputIntent(String location, boolean isStart);
        void onLanguageSwitchIntent(VoskSpeechRecognizerService.Language language);

        void onListeningStarted();
        void onListeningStopped();
        void onRecognitionResult(String text);
        void onError(String errorMsg);
    }

    public OfflineVoiceAssistantService(Context context) {
        this.context = context;
    }

    /**
     * 初始化完全离线语音助手
     */
    public void init(VoiceService voiceService, VoiceAssistantCallback callback) {
        this.voiceService = voiceService;
        this.callback = callback;

        // 初始化本地意图引擎
        intentEngine = new LocalIntentEngine(context);
        Log.d(TAG, "本地意图引擎初始化完成");

        // 初始化Vosk识别器
        initializeVoskRecognizer();
    }

    /**
     * 初始化Vosk识别器
     */
    private void initializeVoskRecognizer() {
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

                // 等待模型加载完成
                int retries = 0;
                while (!voskRecognizer.isInitialized() && retries < 50) {
                    Thread.sleep(100);
                    retries++;
                }

                if (voskRecognizer.isInitialized()) {
                    isInitialized = true;
                    Log.d(TAG, "✓ Vosk 离线识别初始化完成");
                    notifyStatus("离线语音识别已就绪");
                    speak("语音助手已启动，完全离线模式");
                } else {
                    Log.e(TAG, "✗ Vosk 初始化超时");
                    notifyError("语音识别初始化失败");
                }

            } catch (Exception e) {
                Log.e(TAG, "✗ Vosk 初始化失败", e);
                isInitialized = false;
                notifyError("语音识别初始化失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 开始语音识别
     */
    public void startListening() {
        if (!isInitialized) {
            notifyError("语音识别服务未初始化");
            speak("语音识别尚未就绪，请稍候");
            return;
        }

        if (isListening) {
            Log.d(TAG, "已在监听中");
            return;
        }

        isListening = true;

        if (callback != null) {
            callback.onListeningStarted();
        }

        voskRecognizer.startListening();
        Log.d(TAG, "开始语音识别（完全离线模式 - " +
                voskRecognizer.getCurrentLanguage().displayName + "）");
    }

    /**
     * 停止语音识别
     */
    public void stopListening() {
        if (voskRecognizer != null) {
            voskRecognizer.stopListening();
        }

        isListening = false;

        if (callback != null) {
            callback.onListeningStopped();
        }
    }

    /**
     * 处理Vosk识别结果
     */
    private void handleVoskResult(ArrayList<String> results) {
        isListening = false;

        if (callback != null) {
            callback.onListeningStopped();
        }

        if (results != null && !results.isEmpty()) {
            String recognizedText = results.get(0);
            Log.d(TAG, "识别结果: " + recognizedText);

            if (callback != null) {
                String langTag = " [" + voskRecognizer.getCurrentLanguage().displayName + "]";
                callback.onRecognitionResult(recognizedText + langTag);
            }

            // 处理识别结果
            processText(recognizedText);
        } else {
            handleError("未识别到语音");
        }
    }

    /**
     * 处理Vosk识别错误
     */
    private void handleVoskError(String errorMsg) {
        isListening = false;
        Log.e(TAG, "Vosk 识别错误: " + errorMsg);

        if (callback != null) {
            callback.onListeningStopped();
        }

        handleError("语音识别失败: " + errorMsg);
    }

    /**
     * 处理识别文本
     */
    public void processText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Log.d(TAG, "处理输入: " + text);

        // 检查是否是位置输入指令
        if (handleLocationInput(text)) {
            return;
        }

        // 使用本地意图引擎识别
        LocalIntentEngine.IntentResult result = intentEngine.recognize(text);
        Log.d(TAG, "意图识别结果: " + result.toString());

        dispatchIntent(result);
    }

    /**
     * 处理位置输入（新增功能）
     */
    private boolean handleLocationInput(String text) {
        text = text.toLowerCase();

        // 中文位置输入
        if (text.startsWith("我在") || text.startsWith("当前位置")) {
            String location = extractLocation(text);
            if (location != null && callback != null) {
                callback.onLocationInputIntent(location, true);
                speak("已设置当前位置为" + location);
                return true;
            }
        }

        // 英文位置输入
        if (text.startsWith("i am at") || text.startsWith("current location")) {
            String location = extractLocationEnglish(text);
            if (location != null && callback != null) {
                callback.onLocationInputIntent(location, true);
                speak("Current location set to " + location);
                return true;
            }
        }

        // 粤语位置输入
        if (text.startsWith("我喺") || text.startsWith("而家喺")) {
            String location = extractLocationCantonese(text);
            if (location != null && callback != null) {
                callback.onLocationInputIntent(location, true);
                speak("已设置当前位置为" + location);
                return true;
            }
        }

        return false;
    }

    /**
     * 提取位置（中文）
     */
    private String extractLocation(String text) {
        String[] locations = {"浴室", "门口", "楼梯", "电梯", "厕所", "洗手间",
                "出口", "入口", "办公室", "会议室", "大厅"};

        for (String loc : locations) {
            if (text.contains(loc)) {
                return loc;
            }
        }
        return null;
    }

    /**
     * 提取位置（英文）
     */
    private String extractLocationEnglish(String text) {
        String[] locations = {"bathroom", "entrance", "stairs", "elevator", "toilet",
                "exit", "office", "hall", "meeting room"};

        for (String loc : locations) {
            if (text.contains(loc)) {
                return loc;
            }
        }
        return null;
    }

    /**
     * 提取位置（粤语）
     */
    private String extractLocationCantonese(String text) {
        String[] locations = {"洗手间", "门口", "楼梯", "升降机", "厕所",
                "出口", "入口", "办公室", "会议室", "大堂"};

        for (String loc : locations) {
            if (text.contains(loc)) {
                return loc;
            }
        }
        return null;
    }

    /**
     * 分发意图
     */
    private void dispatchIntent(LocalIntentEngine.IntentResult result) {
        if (callback == null) {
            return;
        }

        // ✅ 修复：直接访问 public 字段 intent，而不是调用 result.getIntent()
        switch (result.intent) {
            case NAVIGATE:
                // ✅ 修复：直接访问 public 字段 destination，而不是 result.getEntity(...)
                String destination = result.destination;
                if (destination != null) {
                    callback.onNavigateIntent(destination);
                } else {
                    // ✅ 修复：直接访问 public 字段 rawText，而不是 result.getRawText()
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
                // ✅ 修复：直接访问 rawText
                callback.onHelpIntent(result.rawText);
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
                // ✅ 修复：直接访问 rawText
                callback.onUnknownIntent(result.rawText);
                break;
        }
    }

    public void switchLanguage(VoskSpeechRecognizerService.Language language) {
        switchLanguage(language, true); // 默认触发回调
    }

    /**
     * 切换语言
     */
    public void switchLanguage(VoskSpeechRecognizerService.Language language, boolean notifyCallback) {
        if (voskRecognizer != null) {
            voskRecognizer.switchLanguage(language);
            speak("已切换到" + language.displayName + "模式");

            if (notifyCallback && callback != null) {
                callback.onLanguageSwitchIntent(language);
            }
        }
    }

    /**
     * 获取当前语言
     */
    public VoskSpeechRecognizerService.Language getCurrentLanguage() {
        if (voskRecognizer != null) {
            return voskRecognizer.getCurrentLanguage();
        }
        return VoskSpeechRecognizerService.Language.CHINESE;
    }

    /**
     * 获取已加载的语言列表
     */
    public ArrayList<VoskSpeechRecognizerService.Language> getAvailableLanguages() {
        if (voskRecognizer != null) {
            return voskRecognizer.getLoadedLanguages();
        }
        return new ArrayList<>();
    }

    /**
     * 获取服务状态
     */
    public String getServiceStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("语音识别: ").append(isInitialized ? "✓ 就绪" : "✗ 未就绪").append("\n");

        if (voskRecognizer != null) {
            sb.append("当前语言: ").append(voskRecognizer.getCurrentLanguage().displayName).append("\n");
            sb.append("\n模型状态:\n").append(voskRecognizer.getModelStatus());
        }

        return sb.toString();
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * 检查是否正在监听
     */
    public boolean isListening() {
        return isListening;
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
     * 通知状态
     */
    private void notifyStatus(String status) {
        Log.d(TAG, status);
    }

    /**
     * 通知错误
     */
    private void notifyError(String errorMsg) {
        if (callback != null) {
            callback.onError(errorMsg);
        }
    }

    /**
     * 处理错误
     */
    private void handleError(String errorMsg) {
        notifyError(errorMsg);
        speak(errorMsg);
    }

    /**
     * 销毁资源
     */
    public void destroy() {
        if (voskRecognizer != null) {
            voskRecognizer.destroy();
            voskRecognizer = null;
        }
        callback = null;
        isInitialized = false;
    }
}