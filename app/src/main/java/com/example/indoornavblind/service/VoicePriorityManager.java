package com.example.indoornavblind.service;

import android.util.Log;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 语音优先级管理器
 * 
 * 功能：
 * 1. 管理所有语音播报的优先级
 * 2. 防止多个语音同时播报造成打架
 * 3. 支持高优先级打断低优先级
 * 4. 语音队列管理
 * 
 * 优先级级别：
 * - CRITICAL (100)    - 紧急警告（如危险提示）
 * - NAVIGATION (80)   - 导航指令（导航时的下一步指令）
 * - VOICE_COMMAND (70)- 语音命令反馈（助手正在听/理解了命令）
 * - LOCATION (60)     - 定位播报（你在哪、周围有什么）
 * - INFORMATION (40)  - 信息播报（一般信息）
 * - NORMAL (20)       - 普通消息（不重要的提示）
 */
public class VoicePriorityManager {
    private static final String TAG = "VoicePriorityManager";
    
    // 单例
    private static volatile VoicePriorityManager instance;
    
    // 语音服务
    private VoiceService voiceService;
    
    // 当前正在播报的语音
    private VoiceAnnouncement currentAnnouncement;
    
    // 等待队列（按优先级排序）
    private PriorityQueue<VoiceAnnouncement> queue;
    
    // 线程锁
    private final ReentrantLock lock = new ReentrantLock();
    
    // 是否正在播报
    private boolean isSpeaking = false;
    
    // 状态监听器
    private StateListener stateListener;
    
    // 优先级常量
    public static final int PRIORITY_CRITICAL = 100;     // 紧急
    public static final int PRIORITY_NAVIGATION = 80;    // 导航
    public static final int PRIORITY_VOICE_COMMAND = 70; // 语音命令
    public static final int PRIORITY_LOCATION = 60;      // 定位
    public static final int PRIORITY_INFORMATION = 40;   // 信息
    public static final int PRIORITY_NORMAL = 20;        // 普通
    
    private VoicePriorityManager() {
        queue = new PriorityQueue<>((a, b) -> b.priority - a.priority);
    }
    
    /**
     * 获取单例
     */
    public static VoicePriorityManager getInstance() {
        if (instance == null) {
            synchronized (VoicePriorityManager.class) {
                if (instance == null) {
                    instance = new VoicePriorityManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化（设置VoiceService）
     */
    public void init(VoiceService voiceService) {
        this.voiceService = voiceService;
        Log.d(TAG, "VoicePriorityManager initialized");
    }
    
    /**
     * 播报语音（根据优先级）
     * 
     * @param text 播报内容
     * @param priority 优先级
     * @param canInterrupt 是否允许被更高优先级打断
     * @param callback 播报完成回调
     */
    public void announce(String text, int priority, boolean canInterrupt, 
                        AnnouncementCallback callback) {
        lock.lock();
        try {
            VoiceAnnouncement announcement = new VoiceAnnouncement(
                text, priority, canInterrupt, callback
            );
            
            Log.d(TAG, "New announcement: [P" + priority + "] " + text);
            
            // 如果没有正在播报，或者新的优先级更高且当前允许被打断
            if (!isSpeaking) {
                // 直接播报
                speakImmediately(announcement);
            } else if (currentAnnouncement != null && 
                      currentAnnouncement.canInterrupt && 
                      priority > currentAnnouncement.priority) {
                // 打断当前播报
                Log.d(TAG, "Interrupting current announcement");
                stopCurrentSpeech();
                speakImmediately(announcement);
            } else {
                // 加入队列
                Log.d(TAG, "Adding to queue");
                queue.offer(announcement);
            }
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 播报语音（简化版，使用默认参数）
     */
    public void announce(String text, int priority) {
        announce(text, priority, true, null);
    }
    
    /**
     * 立即播报（内部方法）
     */
    private void speakImmediately(VoiceAnnouncement announcement) {
        if (voiceService == null) {
            Log.e(TAG, "VoiceService not initialized");
            return;
        }
        
        currentAnnouncement = announcement;
        isSpeaking = true;
        
        // 通知监听器
        if (stateListener != null) {
            stateListener.onSpeechStarted(announcement.text, announcement.priority);
        }
        
        Log.d(TAG, "Speaking: " + announcement.text);
        
        // 播报（在新线程中监听完成）
        new Thread(() -> {
            voiceService.speak(announcement.text, 1.0f);
            
            // 等待播报完成（简单实现：根据文字长度估算时间）
            int estimatedTime = estimateTime(announcement.text);
            try {
                Thread.sleep(estimatedTime);
            } catch (InterruptedException e) {
                Log.w(TAG, "Speech interrupted");
            }
            
            onSpeechCompleted();
        }).start();
    }
    
    /**
     * 播报完成回调
     */
    private void onSpeechCompleted() {
        lock.lock();
        try {
            Log.d(TAG, "Speech completed");
            
            // 触发回调
            if (currentAnnouncement != null && currentAnnouncement.callback != null) {
                currentAnnouncement.callback.onCompleted();
            }
            
            // 通知监听器
            if (stateListener != null) {
                stateListener.onSpeechCompleted();
            }
            
            currentAnnouncement = null;
            isSpeaking = false;
            
            // 播报队列中的下一个
            processQueue();
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 处理队列
     */
    private void processQueue() {
        if (!queue.isEmpty()) {
            VoiceAnnouncement next = queue.poll();
            Log.d(TAG, "Processing next in queue: " + next.text);
            speakImmediately(next);
        }
    }
    
    /**
     * 停止当前播报
     */
    public void stopCurrentSpeech() {
        lock.lock();
        try {
            if (isSpeaking && voiceService != null) {
                Log.d(TAG, "Stopping current speech");
                voiceService.stop();
                
                if (currentAnnouncement != null && currentAnnouncement.callback != null) {
                    currentAnnouncement.callback.onInterrupted();
                }
                
                currentAnnouncement = null;
                isSpeaking = false;
                
                // 通知监听器
                if (stateListener != null) {
                    stateListener.onSpeechInterrupted();
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 清空队列
     */
    public void clearQueue() {
        lock.lock();
        try {
            Log.d(TAG, "Clearing queue, size: " + queue.size());
            queue.clear();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 停止所有播报（包括当前和队列）
     */
    public void stopAll() {
        lock.lock();
        try {
            Log.d(TAG, "Stopping all announcements");
            stopCurrentSpeech();
            clearQueue();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 估算播报时间（毫秒）
     */
    private int estimateTime(String text) {
        // 简单估算：中文每字0.3秒，英文每词0.5秒
        int chineseCount = 0;
        int otherCount = 0;
        
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseCount++;
            } else if (Character.isLetterOrDigit(c)) {
                otherCount++;
            }
        }
        
        return (int) (chineseCount * 300 + otherCount * 50) + 500; // 加500ms缓冲
    }
    
    /**
     * 获取当前状态
     */
    public boolean isSpeaking() {
        return isSpeaking;
    }
    
    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return queue.size();
    }
    
    /**
     * 设置状态监听器
     */
    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }
    
    /**
     * 语音播报数据类
     */
    private static class VoiceAnnouncement {
        String text;
        int priority;
        boolean canInterrupt;
        AnnouncementCallback callback;
        long timestamp;
        
        VoiceAnnouncement(String text, int priority, boolean canInterrupt, 
                         AnnouncementCallback callback) {
            this.text = text;
            this.priority = priority;
            this.canInterrupt = canInterrupt;
            this.callback = callback;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * 播报完成回调接口
     */
    public interface AnnouncementCallback {
        void onCompleted();
        default void onInterrupted() {}
    }
    
    /**
     * 状态监听器接口
     */
    public interface StateListener {
        void onSpeechStarted(String text, int priority);
        void onSpeechCompleted();
        void onSpeechInterrupted();
    }
}
