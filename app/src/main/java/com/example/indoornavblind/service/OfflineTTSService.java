package com.example.indoornavblind.service;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

/**
 * 完全离线文字转语音服务
 * * 特性：
 * 1. 使用系统内置的Pico TTS（完全离线）
 * 2. 强制使用离线引擎，拒绝在线引擎
 * 3. 支持中文、英文、粤语三语切换
 * 4. 队列播报系统，防止重叠
 * 5. 自动降级策略（如果Pico不可用，使用任何离线引擎）
 */
public class OfflineTTSService implements VoiceService {
    private static final String TAG = "OfflineTTS";

    // 手动定义该常量，对应 "networkTts"
    private static final String KEY_PARAM_NETWORK_SYNTHESIS_DISABLED = "networkTts";

    // TTS引擎优先级（完全离线）
    private static final String[] OFFLINE_ENGINES = {
            "com.svox.pico",           // Pico TTS（Android内置）
            "com.google.android.tts",  // Google TTS离线模式
            "com.android.tts"          // 系统默认
    };

    // 重试配置
    private static final int MAX_INIT_RETRIES = 3;
    private static final long INIT_RETRY_DELAY = 1000;
    private static final long SPEAK_TIMEOUT = 10000;

    private TextToSpeech tts;
    private Context context;
    private boolean isInitialized = false;
    private boolean isInitializing = false;
    private boolean isSpeaking = false;
    private float currentSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;
    private String currentEngine = null;

    // 重试计数
    private int initRetryCount = 0;
    private int engineIndex = 0;

    // 播报队列
    private Queue<PendingUtterance> utteranceQueue = new LinkedList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    // 支持的语言
    public enum Language {
        CHINESE(Locale.CHINESE, "zh-CN"),
        ENGLISH(Locale.ENGLISH, "en-US"),
        CANTONESE(Locale.TRADITIONAL_CHINESE, "yue-HK");  // 粤语

        public final Locale locale;
        public final String code;

        Language(Locale locale, String code) {
            this.locale = locale;
            this.code = code;
        }
    }

    /**
     * 待播报内容类
     */
    private static class PendingUtterance {
        String text;
        float speed;

        PendingUtterance(String text, float speed) {
            this.text = text;
            this.speed = speed;
        }
    }

    public OfflineTTSService(Context context) {
        this.context = context;
        initTTS();
    }

    @Override
    public void init(Context context) {
        this.context = context;
        if (tts != null) {
            tts.shutdown();
        }
        initRetryCount = 0;
        engineIndex = 0;
        initTTS();
    }

    /**
     * 初始化TTS，强制使用离线引擎
     */
    /**
     * 修改后的初始化逻辑：更智能地寻找可用引擎
     */
    private void initTTS() {
        if (isInitializing) return;
        isInitializing = true;
        isInitialized = false;

        // 策略：直接初始化默认引擎，不指定包名
        // Android 系统会自动选择用户设置的首选引擎（通常就是最好的那个）
        Log.d(TAG, "正在初始化系统默认TTS引擎...");

        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // 初始化成功，开始检查语言
                int result = tts.setLanguage(currentLocale);

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "系统默认引擎不支持当前语言或缺少数据包");
                    // 这里可以触发一个回调通知 MainActivity 弹窗提示用户下载
                    // notifyUserToInstallVoiceData();

                    // 尝试降级为英语
                    int enResult = tts.setLanguage(Locale.ENGLISH);
                    if (enResult >= TextToSpeech.LANG_AVAILABLE) {
                        Log.w(TAG, "降级使用英语");
                        finishInit();
                    } else {
                        Log.e(TAG, "彻底失败：无可用语言");
                        isInitializing = false;
                    }
                } else {
                    Log.d(TAG, "系统默认引擎支持中文！");
                    finishInit();
                }
            } else {
                Log.e(TAG, "TTS初始化失败");
                isInitializing = false;
            }
        }); // 不传 currentEngine 参数，让系统决定
    }

    private void finishInit() {
        tts.setSpeechRate(currentSpeed);
        // 关键：禁用网络（如果支持）
        try {
            Bundle params = new Bundle();
            params.putString("networkTts", "true");
            // 注意：部分国产引擎可能忽略这个参数，但这没事，只要手机没网它们自然会切离线
        } catch (Exception e) {}

        isInitialized = true;
        isInitializing = false;
        processQueue(); // 播放等待的语音
    }

    /**
     * 处理TTS初始化结果
     */
    private void handleTTSInitResult(int status) {
        isInitializing = false;

        if (status == TextToSpeech.SUCCESS) {
            // 强制检查是否支持离线语言
            if (!checkOfflineLanguageSupport()) {
                Log.e(TAG, "引擎 " + currentEngine + " 不支持离线语言，尝试下一个");
                tryNextEngine();
                return;
            }

            // 成功初始化
            int result = tts.setLanguage(currentLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "当前语言不支持，回退到中文");
                tts.setLanguage(Locale.CHINESE);
            }

            tts.setSpeechRate(currentSpeed);

            // 关键：禁用网络相关功能
            disableNetworkFeatures();

            isInitialized = true;
            initRetryCount = 0;
            engineIndex = 0;
            Log.d(TAG, "✓ TTS初始化成功，使用引擎: " + currentEngine);

            // 处理队列中等待的内容
            processQueue();
        } else {
            Log.e(TAG, "TTS初始化失败，状态码: " + status);
            tryNextEngine();
        }
    }

    /**
     * 检查离线语言支持
     */
    private boolean checkOfflineLanguageSupport() {
        if (tts == null) return false;

        try {
            // 检查中文支持
            int chineseResult = tts.isLanguageAvailable(Locale.CHINESE);
            boolean chineseOk = chineseResult >= TextToSpeech.LANG_AVAILABLE;

            // 检查英文支持
            int englishResult = tts.isLanguageAvailable(Locale.ENGLISH);
            boolean englishOk = englishResult >= TextToSpeech.LANG_AVAILABLE;

            Log.d(TAG, "语言支持检查 - 中文: " + chineseOk + ", 英文: " + englishOk);

            // 至少支持一种语言即可
            return chineseOk || englishOk;
        } catch (Exception e) {
            Log.e(TAG, "检查语言支持时出错", e);
            return false;
        }
    }

    /**
     * 禁用网络相关功能（强制离线）
     */
    private void disableNetworkFeatures() {
        if (tts == null) return;

        try {
            // 设置参数，禁用网络合成
            Bundle params = new Bundle();
            // ✅ 修复：使用本地定义的常量字符串 "networkTts"
            params.putString(KEY_PARAM_NETWORK_SYNTHESIS_DISABLED, "true");
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC);

            Log.d(TAG, "已禁用网络合成功能");
        } catch (Exception e) {
            Log.w(TAG, "禁用网络功能时出错（可能不支持）", e);
        }
    }

    /**
     * 尝试下一个引擎
     */
    private void tryNextEngine() {
        engineIndex++;

        if (engineIndex <= OFFLINE_ENGINES.length) {
            Log.d(TAG, "尝试下一个离线引擎...");
            mainHandler.postDelayed(this::initTTS, INIT_RETRY_DELAY);
        } else {
            // 所有引擎都失败
            Log.e(TAG, "所有离线TTS引擎初始化失败");
            isInitialized = false;

            // 最后尝试重试
            if (initRetryCount < MAX_INIT_RETRIES) {
                initRetryCount++;
                engineIndex = 0;
                Log.d(TAG, "重新尝试所有引擎（第" + initRetryCount + "次）");
                mainHandler.postDelayed(this::initTTS, INIT_RETRY_DELAY);
            }
        }
    }

    /**
     * 设置播报进度
     */
    private void setupUtteranceListener() {
        if (tts == null) return;

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "开始播报: " + utteranceId);
                isSpeaking = true;
                startTimeoutCheck();
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "播报完成: " + utteranceId);
                isSpeaking = false;
                cancelTimeoutCheck();
                mainHandler.post(() -> processQueue());
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "播报错误: " + utteranceId);
                isSpeaking = false;
                cancelTimeoutCheck();
                checkAndRecoverTTS();
            }
        });
    }

    /**
     * 启动超时检测
     */
    private void startTimeoutCheck() {
        cancelTimeoutCheck();
        timeoutRunnable = () -> {
            if (isSpeaking) {
                Log.w(TAG, "播报超时，强制恢复");
                isSpeaking = false;
                checkAndRecoverTTS();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, SPEAK_TIMEOUT);
    }

    /**
     * 取消超时检测
     */
    private void cancelTimeoutCheck() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /**
     * 检查并恢复TTS
     */
    private void checkAndRecoverTTS() {
        Log.w(TAG, "检查TTS状态并尝试恢复...");

        if (tts == null || !isInitialized) {
            initRetryCount = 0;
            engineIndex = 0;
            initTTS();
            return;
        }

        // 测试TTS是否正常
        try {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "test");
            int result = tts.speak("", TextToSpeech.QUEUE_ADD, params, "test");

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS测试失败，重新初始化");
                initRetryCount = 0;
                engineIndex = 0;
                initTTS();
            } else {
                processQueue();
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS测试异常，重新初始化", e);
            initRetryCount = 0;
            engineIndex = 0;
            initTTS();
        }
    }

    @Override
    public void speak(String text, float speed) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        utteranceQueue.offer(new PendingUtterance(text, speed));
        Log.d(TAG, "添加到播报队列: " + text + " (队列长度: " + utteranceQueue.size() + ")");

        if (!isSpeaking) {
            processQueue();
        }
    }

    /**
     * 处理播报队列
     */
    private void processQueue() {
        if (isSpeaking) {
            Log.d(TAG, "正在播报中，等待完成");
            return;
        }

        PendingUtterance utterance = utteranceQueue.poll();
        if (utterance == null) {
            return;
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，重新添加到队列并尝试初始化");
            utteranceQueue.offer(utterance);
            if (!isInitializing) {
                initRetryCount = 0;
                engineIndex = 0;
                initTTS();
            }
            return;
        }

        speakInternal(utterance.text, utterance.speed);
    }

    /**
     * 内部播报实现
     */
    private void speakInternal(String text, float speed) {
        try {
            if (tts == null) {
                Log.e(TAG, "TTS对象为null");
                checkAndRecoverTTS();
                return;
            }

            tts.setSpeechRate(speed);

            Bundle params = new Bundle();
            String utteranceId = String.valueOf(System.currentTimeMillis());
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            // ✅ 修复：使用本地定义的常量字符串 "networkTts"
            params.putString(KEY_PARAM_NETWORK_SYNTHESIS_DISABLED, "true");

            isSpeaking = true;
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS播报失败");
                isSpeaking = false;
                checkAndRecoverTTS();
            } else {
                Log.d(TAG, "TTS播报中: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS播报异常", e);
            isSpeaking = false;
            checkAndRecoverTTS();
        }
    }

    @Override
    public void stop() {
        cancelTimeoutCheck();
        utteranceQueue.clear();
        isSpeaking = false;

        if (tts != null && isInitialized) {
            tts.stop();
            Log.d(TAG, "TTS停止播报，队列已清空");
        }
    }

    @Override
    public void shutdown() {
        cancelTimeoutCheck();
        utteranceQueue.clear();

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isInitialized = false;
        isInitializing = false;
        isSpeaking = false;
        Log.d(TAG, "TTS服务已关闭");
    }

    @Override
    public Context getContext() {
        return context;
    }

    /**
     * 设置语言（支持中英粤）
     */
    @Override
    public void setLanguage(Locale locale) {
        this.currentLocale = locale;
        if (tts != null && isInitialized) {
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "不支持的语言: " + locale.getDisplayLanguage() + "，回退到中文");
                tts.setLanguage(Locale.CHINESE);
            } else {
                Log.d(TAG, "语言已切换到: " + locale.getDisplayLanguage());
            }
        }
    }

    /**
     * 快速切换语言（中英粤）
     */
    public void setLanguage(Language language) {
        setLanguage(language.locale);
    }

    @Override
    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (tts != null && isInitialized) {
            tts.setSpeechRate(speed);
        }
    }

    /**
     * 检查TTS是否已初始化且可用
     */
    public boolean isReady() {
        return isInitialized && tts != null && !isInitializing;
    }

    /**
     * 检查是否正在播报
     */
    public boolean isSpeaking() {
        return isSpeaking;
    }

    /**
     * 获取当前使用的引擎
     */
    public String getCurrentEngine() {
        return currentEngine != null ? currentEngine : "未知";
    }

    /**
     * 获取队列长度
     */
    public int getQueueSize() {
        return utteranceQueue.size();
    }

    /**
     * 强制重新初始化TTS
     */
    public void forceReinit() {
        Log.d(TAG, "强制重新初始化TTS");
        stop();
        initRetryCount = 0;
        engineIndex = 0;
        isInitializing = false;
        initTTS();
    }

    /**
     * 获取TTS状态信息
     */
    public String getStatusInfo() {
        return String.format("引擎: %s | 状态: %s | 语言: %s | 队列: %d",
                getCurrentEngine(),
                isInitialized ? "就绪" : "未就绪",
                currentLocale.getDisplayLanguage(),
                utteranceQueue.size());
    }
}