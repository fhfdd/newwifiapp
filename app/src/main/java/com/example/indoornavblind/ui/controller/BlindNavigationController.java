package com.example.indoornavblind.ui.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import com.example.indoornavblind.database.NavigationNodeDao;
import com.example.indoornavblind.model.Position;
import com.example.indoornavblind.service.AppStateManager;
import com.example.indoornavblind.service.EnhancedPDService;
import com.example.indoornavblind.service.LocationService;
import com.example.indoornavblind.service.VoiceNavigationService;
import com.example.indoornavblind.service.VoiceNavigationServiceV2;
import com.example.indoornavblind.service.VoicePriorityManager;
import com.example.indoornavblind.service.VoiceService;
import com.example.indoornavblind.util.PathParser;

import java.util.List;

/**
 * 盲人友好导航控制器
 * 
 * 功能：
 * 1. 完全语音反馈，无需看屏幕
 * 2. 简单手势控制（单击/双击/长按）
 * 3. 自动管理语音优先级，防止打架
 * 4. 状态管理和协调
 * 
 * 手势说明：
 * - 单击屏幕：重复当前导航指令
 * - 双击屏幕：播报当前位置和周围节点
 * - 长按屏幕：停止导航
 * - 三指滑动：激活语音助手
 * 
 * 语音优先级：
 * 1. 语音助手监听（最高）- 其他操作暂停
 * 2. 导航指令 - 自动播报下一步
 * 3. 定位播报 - 播报完恢复之前状态
 */
public class BlindNavigationController {
    private static final String TAG = "BlindNavigationController";
    
    private Context context;
    
    // 服务组件
    private VoicePriorityManager voicePriorityManager;
    private AppStateManager stateManager;
    private VoiceNavigationServiceV2 voiceNavService;
    private EnhancedPDService pdService;
    private LocationService locationService;
    private VoiceService voiceService;
    
    // 手势检测
    private GestureDetector gestureDetector;
    private Handler handler;
    
    // 监听器
    private ControllerCallback callback;
    
    // 最后一次定位播报时间
    private long lastLocationAnnouncementTime = 0;
    private static final long MIN_LOCATION_INTERVAL = 5000; // 最小定位播报间隔5秒
    
    public BlindNavigationController(Context context,
                                     VoiceNavigationServiceV2 voiceNavService,
                                     EnhancedPDService pdService,
                                     LocationService locationService,
                                     VoiceService voiceService) {
        this.context = context;
        this.voiceNavService = voiceNavService;
        this.pdService = pdService;
        this.locationService = locationService;
        this.voiceService = voiceService;
        
        // 获取管理器
        this.voicePriorityManager = VoicePriorityManager.getInstance();
        this.stateManager = AppStateManager.getInstance();
        
        // 初始化
        init();
    }
    
    /**
     * 初始化
     */
    private void init() {
        handler = new Handler(Looper.getMainLooper());
        
        // 初始化语音优先级管理器
        voicePriorityManager.init(voiceService);
        
        // 设置手势检测
        setupGestureDetector();
        
        // 设置导航回调
        setupNavigationCallbacks();
        
        // 设置状态管理监听
        setupStateListener();
        
        Log.d(TAG, "BlindNavigationController initialized");
    }
    
    /**
     * 设置手势检测
     */
    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 单击：重复当前导航指令
                onSingleTap();
                return true;
            }
            
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 双击：播报当前位置
                onDoubleTap(e);
                return true;
            }
            
            @Override
            public void onLongPress(MotionEvent e) {
                // 长按：停止导航
                onLongPress(e);
            }
        });
    }
    
    /**
     * 设置导航回调
     */
    private void setupNavigationCallbacks() {
        voiceNavService.setNavigationCallback(new VoiceNavigationServiceV2.NavigationCallback() {
            
            @Override
            public void onNavigationStarted(String start, String end, int totalNodes) {
                stateManager.setState(AppStateManager.AppState.NAVIGATING);
                
                String message = String.format("导航开始，从%s到%s，共%d个节点", start, end, totalNodes);
                announceNavigation(message, null);
                
                if (callback != null) {
                    callback.onNavigationStarted();
                }
            }

            @Override
            public void onNodeReached(int nodeIndex, String instruction) {
                // 已经由VoiceNavigationServiceV2播报过，这里只通知UI更新
                if (callback != null) {
                    callback.onNodeReached(nodeIndex);
                }
            }
            
            @Override
            public void onInstructionAnnounced(int nodeIndex, String instruction) {
                if (callback != null) {
                    callback.onInstructionAnnounced(instruction);
                }
            }
            
            @Override
            public void onDirectionUpdated(float direction, String directionDesc) {
                if (callback != null) {
                    callback.onDirectionChanged(directionDesc);
                }
            }
            
            @Override
            public void onUserStopped() {
                // 用户停止移动
                announceInformation("检测到您已停止，请确认方向后继续前进");
                
                if (callback != null) {
                    callback.onUserStopped();
                }
            }
            
            @Override
            public void onUserMoving() {
                if (callback != null) {
                    callback.onUserMoving();
                }
            }
            
            @Override
            public void onNavigationCompleted() {
                stateManager.setState(AppStateManager.AppState.IDLE);
                
                announceNavigation("恭喜，您已到达目的地", () -> {
                    if (callback != null) {
                        callback.onNavigationCompleted();
                    }
                });
            }
            
            @Override
            public void onNavigationStopped() {
                stateManager.setState(AppStateManager.AppState.IDLE);
                announceInformation("导航已停止");
                
                if (callback != null) {
                    callback.onNavigationStopped();
                }
            }
            
            @Override
            public void onNavigationError(String error) {
                announceInformation("导航错误：" + error);
                
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }
    
    /**
     * 设置状态监听
     */
    private void setupStateListener() {
        stateManager.addStateChangeListener(new AppStateManager.StateChangeListener() {
            @Override
            public void onStateChanged(AppStateManager.AppState oldState, 
                                      AppStateManager.AppState newState) {
                Log.d(TAG, "App state changed: " + oldState + " -> " + newState);
                
                if (callback != null) {
                    callback.onStateChanged(newState);
                }
            }
            
            @Override
            public void onStateChangeBlocked(AppStateManager.AppState requestedState, 
                                            AppStateManager.AppState currentState) {
                // 状态切换被阻止
                if (currentState == AppStateManager.AppState.LISTENING) {
                    announceVoiceCommand("正在听您说话，请稍后");
                }
            }
        });
    }
    
    /**
     * 单击屏幕：重复当前导航指令
     */
    private void onSingleTap() {
        Log.d(TAG, "Single tap detected");
        
        // 检查是否可以执行
        if (!stateManager.canPerformAction(AppStateManager.AppState.NAVIGATING)) {
            announceVoiceCommand("正在" + stateManager.getStateDescription(stateManager.getCurrentState()));  // ← 加参数
            return;
        }
        
        // 如果正在导航，重复当前指令
        if (stateManager.isNavigating()) {
            if (voiceNavService != null) {
                voiceNavService.repeatCurrentInstruction();
            }
        } else {
            announceInformation("当前没有导航");
        }
    }
    
    /**
     * 双击屏幕：播报当前位置和周围节点
     */
    private void onDoubleTap(MotionEvent e) {
        Log.d(TAG, "Double tap detected");
        
        // 检查是否可以执行
        if (!stateManager.canPerformAction(AppStateManager.AppState.LOCATING)) {
            announceVoiceCommand("正在" + stateManager.getStateDescription(stateManager.getCurrentState()) + "，请稍后");  // ← 加参数
            return;
        }
        
        // 防止频繁播报
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLocationAnnouncementTime < MIN_LOCATION_INTERVAL) {
            announceInformation("刚才已播报过位置，请稍后再试");
            return;
        }
        lastLocationAnnouncementTime = currentTime;
        
        // 切换到定位状态
        stateManager.setState(AppStateManager.AppState.LOCATING);
        
        // 播报位置信息
        announceLocationInfo();
    }
    
    /**
     * 长按屏幕：停止导航
     */
    private void onLongPress(MotionEvent e) {
        Log.d(TAG, "Long press detected");
        
        if (stateManager.isNavigating()) {
            if (voiceNavService != null) {
                voiceNavService.stopNavigation();
            }
            announceInformation("导航已停止");
        } else {
            announceInformation("当前没有导航");
        }
    }
    
    /**
     * 播报位置信息
     */
    private void announceLocationInfo() {
        new Thread(() -> {
            try {
                // 获取当前位置
                final Position[] currentPos = new Position[1];
                currentPos[0] = pdService.updatePosition();

                if (currentPos[0] == null && locationService != null) {
                    locationService.locate(new LocationService.LocationCallback() {
                        @Override
                        public void onSuccess(Position position) {
                            currentPos[0] = position;
                        }

                        @Override
                        public void onFailure(String error) {
                            Log.e(TAG, "Location failed: " + error);
                        }
                    });
                }

                if (currentPos[0] != null) {
                    String locationInfo = buildLocationInfo(currentPos[0]);
                    announceLocation(locationInfo, () -> {
                        stateManager.restorePreviousState();
                    });
                } else {
                    announceLocation("无法获取当前位置", () -> {
                        stateManager.restorePreviousState();
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error getting location", e);
                announceLocation("获取位置失败", () -> {
                    stateManager.restorePreviousState();
                });
            }
        }).start();
    }
    
    /**
     * 构建位置信息字符串
     */
    private String buildLocationInfo(Position position) {
        StringBuilder info = new StringBuilder();

        if (position.getLabel() != null && !position.getLabel().isEmpty()) {
            info.append("您当前在").append(position.getLabel());
        } else {
            info.append("您当前在").append(position.getFloor()).append("楼");
        }

        if (pdService != null) {
            float direction = pdService.getCurrentDirection();
            String directionDesc = pdService.getDirectionDescription(direction);
            info.append("，面朝").append(directionDesc).append("方");
        }

        if (pdService != null && stateManager.isNavigating()) {
            int steps = pdService.getSessionSteps();
            info.append("，已走").append(steps).append("步");
        }

        // 播报附近节点
        List<String> nearbyPOIs = PathParser.getNearbyPOIs(position.getLabel(), position.getFloor(), 3);
        if (!nearbyPOIs.isEmpty()) {
            info.append("。附近有：").append(String.join("、", nearbyPOIs));
        }

        return info.toString();
    }
    
    /**
     * 播报导航消息（高优先级）
     */
    private void announceNavigation(String message, VoicePriorityManager.AnnouncementCallback callback) {
        voicePriorityManager.announce(
            message, 
            VoicePriorityManager.PRIORITY_NAVIGATION,
            false,  // 导航指令不允许被打断
            callback
        );
    }
    
    /**
     * 播报语音命令（最高优先级）
     */
    private void announceVoiceCommand(String message) {
        voicePriorityManager.announce(
            message, 
            VoicePriorityManager.PRIORITY_VOICE_COMMAND,
            false,
            null
        );
    }
    
    /**
     * 播报定位信息
     */
    private void announceLocation(String message, VoicePriorityManager.AnnouncementCallback callback) {
        voicePriorityManager.announce(
            message, 
            VoicePriorityManager.PRIORITY_LOCATION,
            true,  // 定位信息可以被导航打断
            callback
        );
    }
    
    /**
     * 播报一般信息
     */
    private void announceInformation(String message) {
        voicePriorityManager.announce(
            message, 
            VoicePriorityManager.PRIORITY_INFORMATION,
            true,
            null
        );
    }
    
    /**
     * 开始导航
     */
    public void startNavigation(String startLocation, String endLocation) {
        if (stateManager.isListening()) {
            announceVoiceCommand("正在听您说话，请稍后");
            return;
        }
        
        if (voiceNavService != null) {
            voiceNavService.startVoiceNavigation(startLocation, endLocation);
        }
    }
    
    /**
     * 停止导航
     */
    public void stopNavigation() {
        if (voiceNavService != null) {
            voiceNavService.stopNavigation();
        }
    }
    
    /**
     * 激活语音助手
     */
    public void activateVoiceAssistant() {
        if (stateManager.setState(AppStateManager.AppState.LISTENING)) {
            announceVoiceCommand("正在听，请说话");
            
            if (callback != null) {
                callback.onVoiceAssistantActivated();
            }
        }
    }
    
    /**
     * 语音助手结束
     */
    public void deactivateVoiceAssistant() {
        stateManager.setState(AppStateManager.AppState.IDLE, true);
        
        if (callback != null) {
            callback.onVoiceAssistantDeactivated();
        }
    }
    
    /**
     * 创建触摸监听器（用于View）
     */
    public View.OnTouchListener createTouchListener() {
        return (v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        };
    }
    
    /**
     * 处理触摸事件
     */
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }
    
    /**
     * 设置回调
     */
    public void setCallback(ControllerCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 清理资源
     */
    public void destroy() {
        if (voiceNavService != null) {
            voiceNavService.stopNavigation();
        }
        
        stateManager.removeStateChangeListener(null);
        voicePriorityManager.stopAll();
    }
    
    /**
     * 控制器回调接口
     */
    public interface ControllerCallback {
        void onNavigationStarted();
        void onNavigationCompleted();
        void onNavigationStopped();
        void onNodeReached(int nodeIndex);
        void onInstructionAnnounced(String instruction);
        void onDirectionChanged(String direction);
        void onUserStopped();
        void onUserMoving();
        void onVoiceAssistantActivated();
        void onVoiceAssistantDeactivated();
        void onStateChanged(AppStateManager.AppState newState);
        void onError(String error);
    }
}
