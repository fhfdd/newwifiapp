package com.example.indoornavblind.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.indoornavblind.util.TTSUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 文字转语音服务实现 - 基于TTSUtil（带优���级管理）
 *
 * 增强内容：
 * 1. 重试机制：TTS初始化失败时自动重试（最多3次）
 * 2. 优先队列系统：防止语音重叠，按优先级播报
 * 3. 超时检测：播报超时自动恢复
 * 4. 状态管理：精确跟踪初始化和播报状态
 * 5. 强制恢复：提供手动重新初始化方法
 * 6. 优先级管理：不同语音类型有不同的优先级和打断策略
 * 7. TTS状态监听：允许外部监听播报状态变化，用于控制语音识别
 */
public class C_TextToSpeechService implements VoiceService {
    private static final String TAG = "TextToSpeechService";

    /**
     * TTS播报状态监听器接口
     * 用于控制语音识别服务，避免回声识别
     */
    public interface TTSSpeechListener {
        /**
         * TTS开始播���时调用（建议停止语音识别）
         */
        void onSpeechStart();

        /**
         * TTS播报完成时调用（可以恢复语音识别）
         */
        void onSpeechDone();

        /**
         * TTS播报出错时调用（可以恢复语音识别）
         */
        void onSpeechError(String errorMessage);
    }

    // 优先级常量
    public static final int PRIORITY_CRITICAL = 100;      // 关键导航指令（左转、右转等），可打断所有
    public static final int PRIORITY_NAVIGATION = 80;     // 一般导航提示，可打断低优先级
    public static final int PRIORITY_LOCATION = 60;       // 位置信息，不可打断助手
    public static final int PRIORITY_ASSISTANT = 40;      // 语音助手回答，不可被打断
    public static final int PRIORITY_INFO = 20;           // 一般信息，最低优先级

    // 重试配置
    private static final int MAX_INIT_RETRIES = 3;
    private static final long INIT_RETRY_DELAY = 1000; // 1秒
    private static final long SPEAK_TIMEOUT = 10000; // 10秒超时

    private TTSUtil ttsUtil;
    private Context context;
    private boolean isInitialized = false;
    private boolean isInitializing = false;
    private boolean isSpeaking = false;
    private float currentSpeed = 1.0f;
    private Locale currentLocale = Locale.CHINESE;

    // 重试计数
    private int initRetryCount = 0;

    // 使用ArrayList模拟优先队列
    private List<PendingUtterance> utteranceList;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    // 记录上次语音助手回答
    private String lastAssistantResponse = "";
    private int currentPriority = 0;

    // 重复播报检测
    private String lastSpokenText = "";
    private long lastSpokenTime = 0;
    private static final long DUPLICATE_WINDOW_MS = 3000; // 3秒内相同内容视为重复
    private boolean isPaused = false; // 是否暂停（用户正在听内容）
    private long utteranceCounter = 0; // 用于插入顺序排序

    // TTS状态监听器（用于控制Vosk语音识别）
    private TTSSpeechListener ttsSpeechListener;

    /**
     * 待播报内容类 - 支持优先级
     */
    private static class PendingUtterance {
        String text;
        float speed;
        int priority;
        String source; // 来源：assistant, navigation, location, system
        boolean interruptible; // 是否可以被打断
        String utteranceId;
        long insertionOrder; // 插入顺序，用于相同优先级时的排序

        PendingUtterance(String text, float speed, int priority, String source, boolean interruptible, long insertionOrder) {
            this.text = text;
            this.speed = speed;
            this.priority = priority;
            this.source = source;
            this.interruptible = interruptible;
            this.utteranceId = UUID.randomUUID().toString();
            this.insertionOrder = insertionOrder;
        }
    }

    // 比较器：优先级高的在前，相同优先级按插入顺序
    private Comparator<PendingUtterance> priorityComparator = new Comparator<PendingUtterance>() {
        @Override
        public int compare(PendingUtterance a, PendingUtterance b) {
            if (a.priority != b.priority) {
                return Integer.compare(b.priority, a.priority); // 降序
            }
            return Long.compare(a.insertionOrder, b.insertionOrder); // 插入顺序升序
        }
    };

    public C_TextToSpeechService(Context context) {
        this.context = context;
        utteranceList = new ArrayList<>();
        initTTS();
    }

    @Override
    public void init(Context context) {
        this.context = context;
        if (ttsUtil != null) {
            ttsUtil.stop();
            ttsUtil.release();
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

        if (ttsUtil != null) {
            try {
                ttsUtil.release();
            } catch (Exception e) {
                Log.e(TAG, "关闭旧TTS实例时出错", e);
            }
        }

        Log.d(TAG, "开始初始化TTS，第" + (initRetryCount + 1) + "次尝试");

        // 使用TTSUtil初始化
        ttsUtil = new TTSUtil(context, new TTSUtil.TTSListener() {
            @Override
            public void onInitSuccess() {
                isInitializing = false;
                isInitialized = true;
                initRetryCount = 0; // 重置重试计数

                // 设置默认语言和语速
                ttsUtil.setDefaultLocale(currentLocale);
                ttsUtil.setDefaultSpeechRate(currentSpeed);

                Log.d(TAG, "TTS初始化成功");

                // 处理队列中等待的内容
                processQueue();
            }

            @Override
            public void onInitFailure() {
                isInitializing = false;
                isInitialized = false;
                Log.e(TAG, "TTS初始化失败");

                // 重试逻辑
                if (initRetryCount < MAX_INIT_RETRIES) {
                    initRetryCount++;
                    Log.d(TAG, "TTS初始化失败，" + INIT_RETRY_DELAY + "ms后重试（第" + initRetryCount + "次）");
                    mainHandler.postDelayed(() -> initTTS(), INIT_RETRY_DELAY);
                } else {
                    Log.e(TAG, "TTS初始化失败，已达到最大重试次数");
                }
            }

            @Override
            public void onSpeechStart(String utteranceId) {
                Log.d(TAG, "开始播报: " + utteranceId + " (优先级: " + currentPriority + ")");
                isSpeaking = true;
                startTimeoutCheck();
                // 通知监听器：TTS开始播报，外部应停止语音识别
                if (ttsSpeechListener != null) {
                    ttsSpeechListener.onSpeechStart();
                }
            }

            @Override
            public void onSpeechDone() {
                Log.d(TAG, "播报完成");
                isSpeaking = false;
                currentPriority = 0;
                cancelTimeoutCheck();
                // 通知监听器：TTS播报完成，外部可以恢复语音识别
                if (ttsSpeechListener != null) {
                    ttsSpeechListener.onSpeechDone();
                }
                // 处理队列中的下一条
                mainHandler.post(() -> processQueue());
            }

            @Override
            public void onSpeechError(String errorMessage) {
                Log.e(TAG, "播报错误: " + errorMessage);
                isSpeaking = false;
                currentPriority = 0;
                cancelTimeoutCheck();
                // 通知监听器：TTS播报出错，外部可以恢复语音识别
                if (ttsSpeechListener != null) {
                    ttsSpeechListener.onSpeechError(errorMessage);
                }
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
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (isSpeaking) {
                    Log.w(TAG, "播报超时，强制恢复");
                    isSpeaking = false;
                    currentPriority = 0;
                    checkAndRecoverTTS();
                }
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
        if (ttsUtil == null || !isInitialized) {
            initRetryCount = 0;
            initTTS();
            return;
        }

        // TTS正常，处理队列
        processQueue();
    }

    @Override
    public void speak(String text, float speed) {
        speak(text, speed, PRIORITY_INFO, "system", true);
    }

    /**
     * 新的带优先级speak方法（核心方法）
     */
    public void speak(String text, float speed, int priority, String source, boolean interruptible) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // 重复内容检测：3秒内相同内容视为重复，跳过播报
        long currentTime = System.currentTimeMillis();
        if (text.equals(lastSpokenText) && (currentTime - lastSpokenTime) < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "跳过重复播报（" + (currentTime - lastSpokenTime) + "ms内）: " + text);
            return;
        }

        PendingUtterance utterance = new PendingUtterance(
                text, speed, priority, source, interruptible, utteranceCounter++);
        utteranceList.add(utterance);
        Collections.sort(utteranceList, priorityComparator);
        Log.d(TAG, "添加到播报队列: " + text + " (优先级: " + priority + ", 来源: " + source + ")");

        // 检查是否需要打断当前播报
        if (isSpeaking) {
            if (!utteranceList.isEmpty()) {
                PendingUtterance next = utteranceList.get(0);
                if (next.priority > currentPriority) {
                    // 新消息优先级更高，检查当前是否可打断
                    if (next.interruptible) {
                        Log.d(TAG, "高优先级消息(" + next.priority + ")，打断当前播报(" + currentPriority + ")");
                        ttsUtil.stop();
                        isSpeaking = false;
                        currentPriority = 0;
                        processQueue();
                    } else {
                        Log.d(TAG, "高优先级消息但不可打断，等待当前播报完成");
                    }
                }
            }
        } else {
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

        if (utteranceList.isEmpty()) {
            Log.d(TAG, "队列为空");
            return;
        }

        PendingUtterance utterance = utteranceList.remove(0);
        if (utterance == null) {
            Log.d(TAG, "获取的utterance为null");
            return;
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，重新添加到队列并尝试初始化");
            utteranceList.add(utterance);
            Collections.sort(utteranceList, priorityComparator);
            if (!isInitializing) {
                initRetryCount = 0;
                initTTS();
            }
            return;
        }

        speakInternal(utterance);
    }

    private void speakInternal(PendingUtterance utterance) {
        // 增强的就绪检查
        if (ttsUtil == null) {
            Log.e(TAG, "TTSUtil对象为null，尝试恢复");
            checkAndRecoverTTS();
            return;
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS未初始化，无法播报: " + utterance.text);
            checkAndRecoverTTS();
            return;
        }

        try {
            // 设置语速
            ttsUtil.setDefaultSpeechRate(utterance.speed);
            ttsUtil.setDefaultLocale(currentLocale);

            currentPriority = utterance.priority;

            // 记录本次播报内容（用于重复检测）
            lastSpokenText = utterance.text;
            lastSpokenTime = System.currentTimeMillis();

            Log.d(TAG, "调用TTSUtil.speak(): text=" + utterance.text + ", speed=" + utterance.speed + ", priority=" + utterance.priority);
            // TTSUtil.speak(String text, String utteranceId, Locale locale, float speechRate, float pitch)
            ttsUtil.speak(utterance.text, utterance.utteranceId, currentLocale, utterance.speed, 1.0f);

            Log.d(TAG, "TTS播报请求成��: " + utterance.text + " (优先级: " + utterance.priority + ", 语速: " + utterance.speed + ")");
        } catch (Exception e) {
            Log.e(TAG, "TTS播报异常: " + e.getMessage(), e);
            isSpeaking = false;
            currentPriority = 0;
            checkAndRecoverTTS();
        }
    }

    @Override
    public void stop() {
        cancelTimeoutCheck();
        utteranceList.clear();
        isSpeaking = false;
        currentPriority = 0;

        if (ttsUtil != null && isInitialized) {
            ttsUtil.stop();
            Log.d(TAG, "TTS停止播报，队列已清空");
        }
    }

    @Override
    public void shutdown() {
        cancelTimeoutCheck();
        utteranceList.clear();

        if (ttsUtil != null) {
            ttsUtil.stop();
            ttsUtil.release();
            ttsUtil = null;
        }
        isInitialized = false;
        isInitializing = false;
        isSpeaking = false;
        currentPriority = 0;
        Log.d(TAG, "TTS服务已关闭");
    }

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public void setLanguage(Locale locale) {
        this.currentLocale = locale;
        if (ttsUtil != null && isInitialized) {
            ttsUtil.setDefaultLocale(locale);
        }
    }

    @Override
    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (ttsUtil != null && isInitialized) {
            ttsUtil.setDefaultSpeechRate(speed);
        }
    }

    /**
     * 检查TTS是否已初始化且可用
     */
    public boolean isReady() {
        return isInitialized && ttsUtil != null && !isInitializing;
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
        return utteranceList.size();
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

    // ==================== 新增功能方法 ====================

    /**
     * 获取上次语音助手回答
     */
    public String getLastAssistantResponse() {
        return lastAssistantResponse;
    }

    /**
     * 重新播报上次语音助手回答
     */
    public void repeatLastAssistantResponse() {
        if (!lastAssistantResponse.isEmpty()) {
            Log.d(TAG, "重新播报上次助手回答: " + lastAssistantResponse);
            speak(lastAssistantResponse, currentSpeed, PRIORITY_ASSISTANT, "assistant", false);
        }
    }

    /**
     * 清除非助手语音的队列
     */
    public void clearNonAssistantQueue() {
        if (utteranceList.isEmpty()) return;

        List<PendingUtterance> newList = new ArrayList<>();
        for (PendingUtterance utterance : utteranceList) {
            if ("assistant".equals(utterance.source)) {
                newList.add(utterance);
            }
        }
        utteranceList = newList;
        Collections.sort(utteranceList, priorityComparator);
        Log.d(TAG, "已清除非助手语音队列");
    }

    /**
     * 暂停所有非关键播报（用户正在听内容时调用）
     */
    public void pauseNonCritical() {
        isPaused = true;
        Log.d(TAG, "已暂停非关键播报");
    }

    /**
     * 恢复所有播报
     */
    public void resumeAll() {
        isPaused = false;
        Log.d(TAG, "已恢复所有播报");
    }

    // ==================== 便捷方法 ====================

    /**
     * 播报关键导航指令（可打断所有）
     */
    public void speakCriticalNavigation(String text) {
        speak(text, currentSpeed, PRIORITY_CRITICAL, "navigation", true);
    }

    /**
     * 播报一般导航提示（不可打断助手）
     */
    public void speakNavigation(String text) {
        speak(text, currentSpeed, PRIORITY_NAVIGATION, "navigation", false);
    }

    /**
     * 播报位置信息（低优先级）
     */
    public void speakLocation(String text) {
        speak(text, currentSpeed, PRIORITY_LOCATION, "location", false);
    }

    /**
     * 播报语音助手回答（不可被打断）
     */
    public void speakAssistant(String text) {
        speak(text, currentSpeed, PRIORITY_ASSISTANT, "assistant", false);
    }

    /**
     * 播报系统信息（最低优先级）
     */
    public void speakInfo(String text) {
        speak(text, currentSpeed, PRIORITY_INFO, "system", true);
    }

    /**
     * 检查是否有高优先级消息在队列中
     */
    public boolean hasCriticalMessages() {
        for (PendingUtterance utterance : utteranceList) {
            if (utterance.priority >= PRIORITY_CRITICAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前播报的优先级
     */
    public int getCurrentPriority() {
        return currentPriority;
    }

    /**
     * 获取队列中最高优先级
     */
    public int getHighestQueuedPriority() {
        if (utteranceList.isEmpty()) return 0;
        PendingUtterance peek = utteranceList.get(0);
        return peek != null ? peek.priority : 0;
    }

    /**
     * 直接插队播报（用于紧急情况）
     */
    public void speakImmediately(String text, int priority, String source) {
        // 停止当前播报
        if (ttsUtil != null && isSpeaking) {
            ttsUtil.stop();
            isSpeaking = false;
            currentPriority = 0;
        }

        // 清空队列
        utteranceList.clear();

        // 立即播报
        PendingUtterance utterance = new PendingUtterance(
                text, currentSpeed, priority, source, true, utteranceCounter++);
        utteranceList.add(utterance);

        processQueue();
    }

    // ==================== TTS状态监听器相关 ====================

    /**
     * 设置TTS播报状态监听器
     * 用于控制语音识别服务，避免回声识别死循环
     * @param listener ���听器，可以为null（取消监听）
     */
    public void setTTSSpeechListener(TTSSpeechListener listener) {
        this.ttsSpeechListener = listener;
        Log.d(TAG, "TTS状态监听器已" + (listener != null ? "设置" : "清除"));
    }

    /**
     * 获取当前TTS状态监听器
     */
    public TTSSpeechListener getTTSSpeechListener() {
        return ttsSpeechListener;
    }
}
