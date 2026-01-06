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
 * 文字转语音服务实现 - 增强版
 *
 * 增强内容：
 * 1. 重试机制：TTS初始化失败时自动重试（最多3次）
 * 2. 队列系统：防止语音重叠，按顺序播报
 * 3. 超时检测：播报超时自动恢复
 * 4. 状态管理：精确跟踪初始化和播报状态
 * 5. 强制恢复：提供手动重新初始化方法
 */
public class C_TextToSpeechService implements VoiceService {
    private static final String TAG = "TextToSpeechService";

    // 重试配置
    private static final int MAX_INIT_RETRIES = 3;
    private static final long INIT_RETRY_DELAY = 1000; // 1秒
    private static final long SPEAK_TIMEOUT = 10000; // 10秒超时

    private TextToSpeech tts;
    private Context context;
    private boolean isInitialized = false;
    private boolean isInitializing = false;
    private boolean isSpeaking = false;
    private float currentSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;

    // 重试计数
    private int initRetryCount = 0;

    // 播报队列
    private Queue<PendingUtterance> utteranceQueue = new LinkedList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

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

    public C_TextToSpeechService(Context context) {
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
        initTTS();
    }

    private void initTTS() {
        if (isInitializing) {
            Log.d(TAG, "TTS正在初始化中，跳过重复初始化");
            return;
        }

        isInitializing = true;
        isInitialized = false;

        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "关闭旧TTS实例时出错", e);
            }
        }

        Log.d(TAG, "开始初始化TTS，第" + (initRetryCount + 1) + "次尝试");

        tts = new TextToSpeech(context, status -> {
            isInitializing = false;

            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(currentLocale);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS语言不支持: " + currentLocale.getDisplayLanguage());
                    // 回退到系统默认语言
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(currentSpeed);
                isInitialized = true;
                initRetryCount = 0; // 重置重试计数
                Log.d(TAG, "TTS初始化成功");

                // 处理队列中等待的内容
                processQueue();
            } else {
                Log.e(TAG, "TTS初始化失败，状态码: " + status);
                isInitialized = false;

                // 重试逻辑
                if (initRetryCount < MAX_INIT_RETRIES) {
                    initRetryCount++;
                    Log.d(TAG, "TTS初始化失败，" + INIT_RETRY_DELAY + "ms后重试（第" + initRetryCount + "次）");
                    mainHandler.postDelayed(this::initTTS, INIT_RETRY_DELAY);
                } else {
                    Log.e(TAG, "TTS初始化失败，已达到最大重试次数");
                }
            }
        });

        // 设置进度监听器
        setupUtteranceListener();
    }

    /**
     * 设置播报进度监听器
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
                // 处理队列中的下一条
                mainHandler.post(() -> processQueue());
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "播报错误: " + utteranceId);
                isSpeaking = false;
                cancelTimeoutCheck();
                // 尝试重新初始化并处理队列
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

        // 如果TTS对象为空或未初始化，重新初始化
        if (tts == null || !isInitialized) {
            initRetryCount = 0;
            initTTS();
            return;
        }

        // 尝试播报空字符串测试TTS是否正常
        try {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "test");
            int result = tts.speak("", TextToSpeech.QUEUE_ADD, params, "test");

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS测试失败，重新初始化");
                initRetryCount = 0;
                initTTS();
            } else {
                // TTS正常，处理队列
                processQueue();
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS测试异常，重新初始化", e);
            initRetryCount = 0;
            initTTS();
        }
    }

    @Override
    public void speak(String text, float speed) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // 添加到队列
        utteranceQueue.offer(new PendingUtterance(text, speed));
        Log.d(TAG, "添加到播报队列: " + text + " (队列长度: " + utteranceQueue.size() + ")");

        // 如果当前没有在播报，开始处理队列
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
            Log.d(TAG, "队列为空");
            return;
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，重新添加到队列并尝试初始化");
            utteranceQueue.offer(utterance); // 重新添加到队列
            if (!isInitializing) {
                initRetryCount = 0;
                initTTS();
            }
            return;
        }

        speakInternal(utterance.text, utterance.speed);
    }

    private void speakInternal(String text, float speed) {
        try {
            if (tts == null) {
                Log.e(TAG, "TTS对象为null");
                checkAndRecoverTTS();
                return;
            }

            // 设置语速
            tts.setSpeechRate(speed);

            Bundle params = new Bundle();
            String utteranceId = String.valueOf(System.currentTimeMillis());
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

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

    @Override
    public void setLanguage(Locale locale) {
        this.currentLocale = locale;
        if (tts != null && isInitialized) {
            int result = tts.setLanguage(locale);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "不支持的语言: " + locale.getDisplayLanguage());
            }
        }
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
        isInitializing = false;
        initTTS();
    }
}
