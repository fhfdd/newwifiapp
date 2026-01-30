package com.example.indoornavblind.service;

import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用状态管理器 - 单例模式
 *
 * 功能：
 * 1. 管理应用全局状态（IDLE、NAVIGATING、LISTENING等）
 * 2. 防止非法状态转换
 * 3. 提供状态变化监听
 *
 * 修复：
 * - 添加 getStateDescription() 方法，获取状态的中文描述
 */
public class AppStateManager {
    private static final String TAG = "AppStateManager";
    private static AppStateManager instance;

    // ✅ 状态描述映射
    private static final Map<AppState, String> STATE_DESCRIPTIONS = new HashMap<>();

    static {
        STATE_DESCRIPTIONS.put(AppState.IDLE, "空闲");
        STATE_DESCRIPTIONS.put(AppState.NAVIGATING, "导航中");
        STATE_DESCRIPTIONS.put(AppState.LOCATING, "定位中");
        STATE_DESCRIPTIONS.put(AppState.LISTENING, "正在听");
    }

    // 当前应用状态
    private AppState currentState = AppState.IDLE;

    // 上一个状态（用于快速恢复）
    private AppState previousState = AppState.IDLE;

    // 状态变化监听器
    private List<StateChangeListener> listeners = new ArrayList<>();

    // 应用状态枚举
    public enum AppState {
        IDLE,           // 空闲状态
        NAVIGATING,     // 正在导航
        LISTENING,      // 正在听（语音输入）
        LOCATING        // 正在定位
    }

    /**
     * 获取单例实例
     */
    public synchronized static AppStateManager getInstance() {
        if (instance == null) {
            instance = new AppStateManager();
        }
        return instance;
    }

    /**
     * ✅ 新增方法：获取状态描述
     * @param state 应用状态
     * @return 状态的中文描述
     */
    public String getStateDescription(AppState state) {
        return STATE_DESCRIPTIONS.getOrDefault(state, "未知状态");
    }

    /**
     * 获取当前状态描述
     * @return 当前状态的中文描述
     */
    public String getCurrentStateDescription() {
        return getStateDescription(currentState);
    }

    /**
     * 获取当前状态
     */
    public AppState getCurrentState() {
        return currentState;
    }

    /**
     * 获取上一个状态
     */
    public AppState getPreviousState() {
        return previousState;
    }

    /**
     * 设置新状态（原子操作）
     * @param newState 新状态
     * @return 是否成功设置
     */
    public synchronized boolean setState(AppState newState) {
        return setState(newState, false);
    }

    /**
     * 设置新状态（可选强制覆盖）
     * @param newState 新状态
     * @param force 是否强制设置（忽略状态转换限制）
     * @return 是否成功设置
     */
    public synchronized boolean setState(AppState newState, boolean force) {
        if (newState == null) {
            Log.w(TAG, "Attempted to set null state");
            return false;
        }

        // 如果已经是该状态，不需要转换
        if (currentState == newState) {
            Log.d(TAG, "Already in state " + newState);
            return true;
        }

        // 检查是否允许状态转换
        if (!force && !isValidTransition(currentState, newState)) {
            Log.w(TAG, "Invalid state transition: " + currentState + " -> " + newState);
            notifyStateChangeBlocked(newState, currentState);
            return false;
        }

        // 保存上一个状态并设置新状态
        AppState oldState = currentState;
        previousState = oldState;
        currentState = newState;

        Log.d(TAG, "State changed: " + oldState + " -> " + newState);
        notifyStateChanged(oldState, newState);

        return true;
    }

    /**
     * 恢复到上一个状态
     * @return 是否成功恢复
     */
    public synchronized boolean restorePreviousState() {
        if (previousState != null && previousState != currentState) {
            return setState(previousState, true);
        }
        return false;
    }

    /**
     * 检查当前状态是否允许执行某个操作
     */
    public boolean canPerformAction(AppState requiredState) {
        return currentState == requiredState || currentState == AppState.IDLE;
    }

    /**
     * 检查是否在导航状态
     */
    public boolean isNavigating() {
        return currentState == AppState.NAVIGATING;
    }

    /**
     * 检查是否在监听状态
     */
    public boolean isListening() {
        return currentState == AppState.LISTENING;
    }

    /**
     * 检查是否在定位状态
     */
    public boolean isLocating() {
        return currentState == AppState.LOCATING;
    }

    /**
     * 检查是否在空闲状态
     */
    public boolean isIdle() {
        return currentState == AppState.IDLE;
    }

    /**
     * 验证状态转换是否有效
     */
    private boolean isValidTransition(AppState from, AppState to) {
        // 定义允许的状态转换规则
        switch (from) {
            case IDLE:
                // 从IDLE可以转到任何状态
                return true;

            case NAVIGATING:
                // 导航中可以转到IDLE或LISTENING（可以打断导航进行语音输入）
                return to == AppState.IDLE || to == AppState.LISTENING;

            case LISTENING:
                // 监听中可以转到IDLE或NAVIGATING
                return to == AppState.IDLE || to == AppState.NAVIGATING;

            case LOCATING:
                // 定位完成后恢复到IDLE或NAVIGATING
                return to == AppState.IDLE || to == AppState.NAVIGATING;

            default:
                return false;
        }
    }

    /**
     * 添加状态变化监听器
     */
    public synchronized void addStateChangeListener(StateChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            Log.d(TAG, "Listener added, total: " + listeners.size());
        }
    }

    /**
     * 移除状态变化监听器
     */
    public synchronized void removeStateChangeListener(StateChangeListener listener) {
        if (listener != null && listeners.remove(listener)) {
            Log.d(TAG, "Listener removed, remaining: " + listeners.size());
        }
    }

    /**
     * 通知所有监听器状态已改变
     */
    private void notifyStateChanged(AppState oldState, AppState newState) {
        List<StateChangeListener> listenersCopy;
        synchronized (this) {
            listenersCopy = new ArrayList<>(listeners);
        }

        for (StateChangeListener listener : listenersCopy) {
            try {
                listener.onStateChanged(oldState, newState);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener", e);
            }
        }
    }

    /**
     * 通知所有监听器状态转换被阻止
     */
    private void notifyStateChangeBlocked(AppState requestedState, AppState currentState) {
        List<StateChangeListener> listenersCopy;
        synchronized (this) {
            listenersCopy = new ArrayList<>(listeners);
        }

        for (StateChangeListener listener : listenersCopy) {
            try {
                listener.onStateChangeBlocked(requestedState, currentState);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener", e);
            }
        }
    }

    /**
     * 状态变化监听器接口
     */
    public interface StateChangeListener {
        /**
         * 状态已改变
         * @param oldState 旧状态
         * @param newState 新状态
         */
        void onStateChanged(AppState oldState, AppState newState);

        /**
         * 状态转换被阻止
         * @param requestedState 请求的新状态
         * @param currentState 当前状态
         */
        void onStateChangeBlocked(AppState requestedState, AppState currentState);
    }
}
