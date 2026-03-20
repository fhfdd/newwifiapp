package com.example.indoornavblind.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.database.entity.NavigationNodeEntity;
import java.util.List;
import java.util.Locale;

/**
 * 语音导航服务 - WiFi定位失败时的备用导航方案
 * 
 * 功能：
 * 1. 语音输入起点和终点
 * 2. 基于步数和时间的智能播报
 * 3. 根据移动状态决定播报时机
 * 4. 支持方向判断（东西南北）
 * 5. 不受用户转向影响
 */
public class VoiceNavigationService {
    private static final String TAG = "VoiceNavigationService";
    
    private Context context;
    private NavigationNodeDao navigationNodeDao;
    private EnhancedPDService pdService;
    private VoiceService voiceService;
    
    // 导航状态
    private boolean isNavigating = false;
    private String currentPathId;
    private List<NavigationNodeEntity> pathNodes;
    private int currentNodeIndex = 0;
    private int navigationStartSteps = 0;
    
    // 播报控制
    private Handler handler;
    private static final float AVERAGE_STEP_TIME = 0.6f; // 成年人平均每步时间（秒）
    private static final int ARRIVAL_THRESHOLD_STEPS = 3; // 到达节点的步数误差范围
    private long lastAnnouncementTime = 0;
    private static final long MIN_ANNOUNCEMENT_INTERVAL = 5000; // 最小播报间隔5秒
    
    // 语言设置
    private Locale currentLocale = Locale.CHINESE;
    
    // 回调
    private NavigationCallback navigationCallback;
    
    public VoiceNavigationService(Context context, 
                                  NavigationNodeDao navigationNodeDao,
                                  EnhancedPDService pdService,
                                  VoiceService voiceService) {
        this.context = context;
        this.navigationNodeDao = navigationNodeDao;
        this.pdService = pdService;
        this.voiceService = voiceService;
        this.handler = new Handler(Looper.getMainLooper());
        
        // 设置PDService监听器
        setupPDServiceListeners();
    }

    /**
     * 设置PDService监听器
     */
    private void setupPDServiceListeners() {
        pdService.setMovementListener(new EnhancedPDService.MovementListener() {
            @Override
            public void onStepDetected(int sessionSteps, int totalSteps) {
                if (isNavigating) {
                    checkNavigationProgress(sessionSteps);
                }
            }

            @Override
            public void onMovementStatusChanged(boolean isMoving) {
                if (isNavigating) {
                    handleMovementStatusChange(isMoving);
                }
            }
        });
        
        pdService.setDirectionListener(new EnhancedPDService.DirectionListener() {
            @Override
            public void onDirectionChanged(float direction, String directionDesc) {
                if (isNavigating && navigationCallback != null) {
                    navigationCallback.onDirectionUpdated(direction, directionDesc);
                }
            }
        });
    }

    /**
     * 开始语音导航
     * @param startLocation 起点位置（通过语音输入识别）
     * @param endLocation 终点位置（通过语音输入识别）
     */
    public void startVoiceNavigation(String startLocation, String endLocation) {
        Log.d(TAG, "Starting voice navigation from " + startLocation + " to " + endLocation);
        
        // 从数据库获取路径节点
        pathNodes = navigationNodeDao.getNodesByStartAndEnd(startLocation, endLocation);
        
        if (pathNodes == null || pathNodes.isEmpty()) {
            announceMessage("抱歉，未找到从" + startLocation + "到" + endLocation + "的路径");
            if (navigationCallback != null) {
                navigationCallback.onNavigationError("路径未找到");
            }
            return;
        }
        
        // 初始化导航状态
        isNavigating = true;
        currentPathId = pathNodes.get(0).getPathId();
        currentNodeIndex = 0;
        navigationStartSteps = pdService.getSessionSteps();
        
        // 重置PDService步数
        pdService.resetSessionSteps();
        
        // 播报导航开始
        String startMessage = String.format("导航开始，从%s到%s，共%d个节点。请开始行走。",
                startLocation, endLocation, pathNodes.size());
        announceMessage(startMessage);
        
        if (navigationCallback != null) {
            navigationCallback.onNavigationStarted(startLocation, endLocation, pathNodes.size());
        }
        
        // 延迟3秒后播报第一条指令
        handler.postDelayed(() -> announceNextInstruction(), 3000);
    }

    /**
     * 检查导航进度
     */
    private void checkNavigationProgress(int currentSteps) {
        if (!isNavigating || pathNodes == null || currentNodeIndex >= pathNodes.size()) {
            return;
        }
        
        NavigationNodeEntity currentNode = pathNodes.get(currentNodeIndex);
        int targetSteps = currentNode.getCumulativeSteps();
        
        // 判断是否到达当前节点
        if (Math.abs(currentSteps - targetSteps) <= ARRIVAL_THRESHOLD_STEPS) {
            onNodeReached(currentNode);
        } else if (currentSteps > targetSteps + ARRIVAL_THRESHOLD_STEPS) {
            // 用户可能走过了，强制进入下一个节点
            Log.w(TAG, "User passed the node, forcing next instruction");
            onNodeReached(currentNode);
        }
    }

    /**
     * 到达节点时的处理
     */
    private void onNodeReached(NavigationNodeEntity node) {
        Log.d(TAG, "Node reached: " + node.getNodeIndex());
        
        // 检查是否为最终目的地
        if (node.isDestination()) {
            finishNavigation();
            return;
        }
        
        // 播报当前节点的指令
        String instruction = getInstructionByLocale(node);
        announceMessage(instruction);
        
        if (navigationCallback != null) {
            navigationCallback.onNodeReached(node.getNodeIndex(), instruction);
        }
        
        // 移动到下一个节点
        currentNodeIndex++;
        
        // 如果还有下一个节点，准备播报
        if (currentNodeIndex < pathNodes.size()) {
            scheduleNextAnnouncement();
        }
    }

    /**
     * 安排下一次播报
     */
    private void scheduleNextAnnouncement() {
        if (!isNavigating || currentNodeIndex >= pathNodes.size()) {
            return;
        }
        
        NavigationNodeEntity nextNode = pathNodes.get(currentNodeIndex);
        int stepsToNext = nextNode.getSegmentSteps();
        
        // 计算预计到达时间
        float estimatedTimeSeconds = stepsToNext * AVERAGE_STEP_TIME;
        long delayMillis = (long) (estimatedTimeSeconds * 1000);
        
        // 确保至少间隔最小播报时间
        long timeSinceLastAnnouncement = System.currentTimeMillis() - lastAnnouncementTime;
        if (timeSinceLastAnnouncement < MIN_ANNOUNCEMENT_INTERVAL) {
            delayMillis = Math.max(delayMillis, MIN_ANNOUNCEMENT_INTERVAL - timeSinceLastAnnouncement);
        }
        
        Log.d(TAG, "Scheduling next announcement in " + (delayMillis / 1000) + " seconds");
        
        // 延迟播报（但会被步数检测提前触发）
        handler.postDelayed(() -> {
            if (isNavigating) {
                announceNextInstruction();
            }
        }, delayMillis);
    }

    /**
     * 播报下一条指令
     */
    private void announceNextInstruction() {
        if (!isNavigating || currentNodeIndex >= pathNodes.size()) {
            return;
        }
        
        NavigationNodeEntity nextNode = pathNodes.get(currentNodeIndex);
        String instruction = getInstructionByLocale(nextNode);
        
        // 添加方向信息
        String directionInfo = getDirectionInfo(nextNode);
        String fullInstruction = instruction + "。" + directionInfo;
        
        announceMessage(fullInstruction);
        
        if (navigationCallback != null) {
            navigationCallback.onInstructionAnnounced(nextNode.getNodeIndex(), fullInstruction);
        }
    }

    /**
     * 获取方向信息
     */
    private String getDirectionInfo(NavigationNodeEntity node) {
        float targetDirection = node.getDirection();
        float currentDirection = pdService.getCurrentDirection();
        
        // 计算方向差
        float directionDiff = Math.abs(targetDirection - currentDirection);
        if (directionDiff > 180) {
            directionDiff = 360 - directionDiff;
        }
        
        String directionDesc = getDirectionDescByLocale(node);
        
        if (directionDiff < 30) {
            return "继续朝" + directionDesc + "方向前进";
        } else if (directionDiff < 90) {
            return "请稍微调整方向，朝" + directionDesc + "前进";
        } else {
            return "请转向" + directionDesc + "方向";
        }
    }

    /**
     * 处理移动状态变化
     */
    private void handleMovementStatusChange(boolean isMoving) {
        if (!isNavigating) {
            return;
        }
        
        if (!isMoving) {
            Log.d(TAG, "User stopped moving");
            announceMessage("检测到您已停止，请确认方向后继续前进");
            
            if (navigationCallback != null) {
                navigationCallback.onUserStopped();
            }
        } else {
            Log.d(TAG, "User started moving");
            if (navigationCallback != null) {
                navigationCallback.onUserMoving();
            }
        }
    }

    /**
     * 完成导航
     */
    private void finishNavigation() {
        Log.d(TAG, "Navigation finished");
        
        announceMessage("恭喜，您已到达目的地");
        isNavigating = false;
        currentNodeIndex = 0;
        pathNodes = null;
        
        if (navigationCallback != null) {
            navigationCallback.onNavigationCompleted();
        }
    }

    /**
     * 停止导航
     */
    public void stopNavigation() {
        if (isNavigating) {
            Log.d(TAG, "Navigation stopped by user");
            isNavigating = false;
            currentNodeIndex = 0;
            pathNodes = null;
            handler.removeCallbacksAndMessages(null);
            
            announceMessage("导航已停止");
            
            if (navigationCallback != null) {
                navigationCallback.onNavigationStopped();
            }
        }
    }

    /**
     * 重复当前指令
     */
    public void repeatCurrentInstruction() {
        if (!isNavigating || pathNodes == null || currentNodeIndex >= pathNodes.size()) {
            announceMessage("当前没有导航指令");
            return;
        }
        
        NavigationNodeEntity currentNode = pathNodes.get(currentNodeIndex);
        String instruction = getInstructionByLocale(currentNode);
        announceMessage(instruction);
    }

    /**
     * 获取当前导航状态
     */
    public NavigationStatus getNavigationStatus() {
        if (!isNavigating) {
            return null;
        }
        
        NavigationStatus status = new NavigationStatus();
        status.isNavigating = true;
        status.currentNodeIndex = currentNodeIndex;
        status.totalNodes = pathNodes != null ? pathNodes.size() : 0;
        status.currentSteps = pdService.getSessionSteps();
        status.isMoving = pdService.isMoving();
        status.currentDirection = pdService.getCurrentDirection();
        status.directionDesc = pdService.getDirectionDescription(status.currentDirection);
        
        return status;
    }

    /**
     * 播报消息
     */
    private void announceMessage(String message) {
        Log.d(TAG, "Announcing: " + message);
        voiceService.speak(message, 1.0f);
        lastAnnouncementTime = System.currentTimeMillis();
    }

    /**
     * 根据语言获取指令
     */
    private String getInstructionByLocale(NavigationNodeEntity node) {
        if (currentLocale.equals(Locale.ENGLISH)) {
            return node.getInstruction_en();
        } else if (currentLocale.getLanguage().equals("yue")) {
            return node.getInstruction_yue();
        } else {
            return node.getInstruction_cn();
        }
    }

    /**
     * 根据语言获取方向描述
     */
    private String getDirectionDescByLocale(NavigationNodeEntity node) {
        if (currentLocale.equals(Locale.ENGLISH)) {
            return node.getDirectionDesc_en();
        } else if (currentLocale.getLanguage().equals("yue")) {
            return node.getDirectionDesc_yue();
        } else {
            return node.getDirectionDesc_cn();
        }
    }

    // Setters
    public void setLocale(Locale locale) {
        this.currentLocale = locale;
    }

    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }

    public boolean isNavigating() {
        return isNavigating;
    }

    /**
     * 导航状态类
     */
    public static class NavigationStatus {
        public boolean isNavigating;
        public int currentNodeIndex;
        public int totalNodes;
        public int currentSteps;
        public boolean isMoving;
        public float currentDirection;
        public String directionDesc;
    }

    /**
     * 导航回调接口
     */
    public interface NavigationCallback {
        void onNavigationStarted(String start, String end, int totalNodes);
        void onNodeReached(int nodeIndex, String instruction);
        void onInstructionAnnounced(int nodeIndex, String instruction);
        void onDirectionUpdated(float direction, String directionDesc);
        void onUserStopped();
        void onUserMoving();
        void onNavigationCompleted();
        void onNavigationStopped();
        void onNavigationError(String error);
    }
}
