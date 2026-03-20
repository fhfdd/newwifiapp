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
 * 语音导航服务（更新版）
 * 
 * 改进：
 * 1. 使用VoicePriorityManager管理语音播报，防止打架
 * 2. 所有导航指令使用NAVIGATION优先级
 * 3. 信息提示使用INFORMATION优先级
 * 4. 支持播报被打断的处理
 */
public class VoiceNavigationServiceV2 {
    private static final String TAG = "VoiceNavServiceV2";
    
    private Context context;
    private NavigationNodeDao navigationNodeDao;
    private EnhancedPDService pdService;
    private VoicePriorityManager voicePriorityManager;
    
    // 导航状态
    private boolean isNavigating = false;
    private String currentPathId;
    private List<NavigationNodeEntity> pathNodes;
    private int currentNodeIndex = 0;
    private int navigationStartSteps = 0;
    
    // 播报控制
    private Handler handler;
    private static final float AVERAGE_STEP_TIME = 0.6f;
    private static final int ARRIVAL_THRESHOLD_STEPS = 3;
    private static final long MIN_ANNOUNCEMENT_INTERVAL = 5000;
    private long lastAnnouncementTime = 0;
    
    // 语言设置
    private Locale currentLocale = Locale.CHINESE;
    
    // 回调
    private NavigationCallback navigationCallback;
    
    // 最后播报的指令（用于重复）
    private String lastInstruction = "";
    
    public VoiceNavigationServiceV2(Context context, 
                                    NavigationNodeDao navigationNodeDao,
                                    EnhancedPDService pdService) {
        this.context = context;
        this.navigationNodeDao = navigationNodeDao;
        this.pdService = pdService;
        this.voicePriorityManager = VoicePriorityManager.getInstance();
        this.handler = new Handler(Looper.getMainLooper());
        
        setupPDServiceListeners();
    }
    
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
    
    public void startVoiceNavigation(String startLocation, String endLocation) {
        Log.d(TAG, "Starting navigation: " + startLocation + " -> " + endLocation);
        
        pathNodes = navigationNodeDao.getNodesByStartAndEnd(startLocation, endLocation);
        
        if (pathNodes == null || pathNodes.isEmpty()) {
            announceInformation("抱歉，未找到从" + startLocation + "到" + endLocation + "的路径");
            if (navigationCallback != null) {
                navigationCallback.onNavigationError("路径未找到");
            }
            return;
        }
        
        isNavigating = true;
        currentPathId = pathNodes.get(0).getPathId();
        currentNodeIndex = 0;
        navigationStartSteps = pdService.getSessionSteps();
        pdService.resetSessionSteps();
        
        String startMessage = String.format("导航开始，从%s到%s，共%d个节点。请开始行走。",
                startLocation, endLocation, pathNodes.size());
        
        announceNavigation(startMessage, () -> {
            if (navigationCallback != null) {
                navigationCallback.onNavigationStarted(startLocation, endLocation, pathNodes.size());
            }

            // 不需要自动播报，等用户开始走动后由步数检测触发
            announceNextInstruction();
        });
    }
    
    private void checkNavigationProgress(int currentSteps) {
        if (!isNavigating || pathNodes == null || currentNodeIndex >= pathNodes.size()) {
            return;
        }
        
        NavigationNodeEntity currentNode = pathNodes.get(currentNodeIndex);
        int targetSteps = currentNode.getCumulativeSteps();
        
        if (Math.abs(currentSteps - targetSteps) <= ARRIVAL_THRESHOLD_STEPS) {
            onNodeReached(currentNode);
        } else if (currentSteps > targetSteps + ARRIVAL_THRESHOLD_STEPS) {
            Log.w(TAG, "User passed the node");
            onNodeReached(currentNode);
        }
    }
    
    private void onNodeReached(NavigationNodeEntity node) {
        Log.d(TAG, "Node reached: " + node.getNodeIndex());
        
        if (node.isDestination()) {
            finishNavigation();
            return;
        }
        
        String instruction = getInstructionByLocale(node);
        lastInstruction = instruction;
        
        announceNavigation(instruction, () -> {
            if (navigationCallback != null) {
                navigationCallback.onNodeReached(node.getNodeIndex(), instruction);
            }
        });
        
        currentNodeIndex++;
    }
    
    private void scheduleNextAnnouncement() {
        if (!isNavigating || currentNodeIndex >= pathNodes.size()) {
            return;
        }
        
        NavigationNodeEntity nextNode = pathNodes.get(currentNodeIndex);
        int stepsToNext = nextNode.getSegmentSteps();
        float estimatedTimeSeconds = stepsToNext * AVERAGE_STEP_TIME;
        long delayMillis = (long) (estimatedTimeSeconds * 1000);
        
        long timeSinceLastAnnouncement = System.currentTimeMillis() - lastAnnouncementTime;
        if (timeSinceLastAnnouncement < MIN_ANNOUNCEMENT_INTERVAL) {
            delayMillis = Math.max(delayMillis, MIN_ANNOUNCEMENT_INTERVAL - timeSinceLastAnnouncement);
        }
        
        handler.postDelayed(() -> {
            if (isNavigating) {
                announceNextInstruction();
            }
        }, delayMillis);
    }
    
    private void announceNextInstruction() {
        if (!isNavigating || currentNodeIndex >= pathNodes.size()) {
            return;
        }
        
        NavigationNodeEntity nextNode = pathNodes.get(currentNodeIndex);
        String instruction = getInstructionByLocale(nextNode);
        String directionInfo = getDirectionInfo(nextNode);
        String fullInstruction = instruction + "。" + directionInfo;
        
        lastInstruction = fullInstruction;
        
        announceNavigation(fullInstruction, () -> {
            if (navigationCallback != null) {
                navigationCallback.onInstructionAnnounced(nextNode.getNodeIndex(), fullInstruction);
            }
        });
    }
    
    private String getDirectionInfo(NavigationNodeEntity node) {
        float targetDirection = node.getDirection();
        float currentDirection = pdService.getCurrentDirection();
        
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
    
    private void handleMovementStatusChange(boolean isMoving) {
        if (!isNavigating) {
            return;
        }
        
        if (!isMoving) {
            announceInformation("检测到您已停止，请确认方向后继续前进");
            if (navigationCallback != null) {
                navigationCallback.onUserStopped();
            }
        } else {
            if (navigationCallback != null) {
                navigationCallback.onUserMoving();
            }
        }
    }
    
    private void finishNavigation() {
        Log.d(TAG, "Navigation finished");
        
        announceNavigation("恭喜，您已到达目的地", () -> {
            if (navigationCallback != null) {
                navigationCallback.onNavigationCompleted();
            }
        });
        
        isNavigating = false;
        currentNodeIndex = 0;
        pathNodes = null;
    }
    
    public void stopNavigation() {
        if (isNavigating) {
            Log.d(TAG, "Navigation stopped");
            isNavigating = false;
            currentNodeIndex = 0;
            pathNodes = null;
            handler.removeCallbacksAndMessages(null);
            
            announceInformation("导航已停止");
            
            if (navigationCallback != null) {
                navigationCallback.onNavigationStopped();
            }
        }
    }
    
    /**
     * 重复当前指令
     */
    public void repeatCurrentInstruction() {
        if (!isNavigating) {
            announceInformation("当前没有导航");
            return;
        }
        
        if (lastInstruction != null && !lastInstruction.isEmpty()) {
            announceNavigation(lastInstruction, null);
        } else {
            announceInformation("暂无导航指令");
        }
    }
    
    /**
     * 播报导航消息（使用优先级管理器）
     */
    private void announceNavigation(String message, VoicePriorityManager.AnnouncementCallback callback) {
        Log.d(TAG, "Announcing (NAVIGATION): " + message);
        voicePriorityManager.announce(
            message, 
            VoicePriorityManager.PRIORITY_NAVIGATION,
            false,  // 导航指令不允许被打断
            callback
        );
        lastAnnouncementTime = System.currentTimeMillis();
    }
    
    /**
     * 播报信息消息（使用优先级管理器）
     */
    private void announceInformation(String message) {
        Log.d(TAG, "Announcing (INFO): " + message);
        voicePriorityManager.announce(
            message, 
            VoicePriorityManager.PRIORITY_INFORMATION,
            true,  // 信息可以被打断
            null
        );
    }
    
    private String getInstructionByLocale(NavigationNodeEntity node) {
        if (currentLocale.equals(Locale.ENGLISH)) {
            return node.getInstruction_en();
        } else if (currentLocale.getLanguage().equals("yue")) {
            return node.getInstruction_yue();
        } else {
            return node.getInstruction_cn();
        }
    }
    
    private String getDirectionDescByLocale(NavigationNodeEntity node) {
        if (currentLocale.equals(Locale.ENGLISH)) {
            return node.getDirectionDesc_en();
        } else if (currentLocale.getLanguage().equals("yue")) {
            return node.getDirectionDesc_yue();
        } else {
            return node.getDirectionDesc_cn();
        }
    }
    
    // Getters and Setters
    public void setLocale(Locale locale) {
        this.currentLocale = locale;
    }
    
    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }
    
    public boolean isNavigating() {
        return isNavigating;
    }
    
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
    
    public static class NavigationStatus {
        public boolean isNavigating;
        public int currentNodeIndex;
        public int totalNodes;
        public int currentSteps;
        public boolean isMoving;
        public float currentDirection;
        public String directionDesc;
    }
    
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
